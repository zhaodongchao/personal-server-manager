package com.serverpanel.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.file.config.FileProperties;
import com.serverpanel.file.entity.FileRecycleBin;
import com.serverpanel.file.mapper.FileRecycleBinMapper;
import com.serverpanel.file.security.PathGuard;
import com.serverpanel.framework.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件回收站：删除入回收站（物理移动 + 落库）、还原、彻底删除、清空、定时清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleService {

    private final PathGuard pathGuard;
    private final FileProperties properties;
    private final FileRecycleBinMapper recycleMapper;

    /** 删除：先校验（非白名单根、目录须为空），再物理移入回收站并记录 */
    public void delete(String rawPath) {
        Path source = pathGuard.resolveExisting(rawPath);
        for (Path root : pathGuard.roots()) {
            if (source.equals(root)) {
                throw new ServiceException(ErrorCode.FILE_ROOT_FORBIDDEN);
            }
        }
        if (Files.isDirectory(source) && !isEmptyDir(source)) {
            throw new ServiceException(ErrorCode.FILE_DIR_NOT_EMPTY);
        }

        Path trash = Path.of(properties.getTrashDir());
        try {
            Files.createDirectories(trash);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "回收站不可用");
        }
        // 时间戳前缀防同名冲突
        String trashName = System.currentTimeMillis() + "_" + source.getFileName();
        Path trashTarget = trash.resolve(trashName);
        try {
            Files.move(source, trashTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // 容器化部署下，白名单根目录与回收站目录是**不同的 bind mount**：
            // 即使同属一个文件系统（st_dev 相同），rename(2) 仍返回 EXDEV，
            // ATOMIC_MOVE 必然失败 —— 对外表现为「移入回收站失败」，整个回收站不可用。
            // 退化为普通 move：跨挂载时由 JDK 自行 copy + delete（目标名带时间戳前缀，
            // 不会覆盖既有条目，非原子移动在这里是安全的）。
            try {
                Files.move(source, trashTarget);
            } catch (IOException e) {
                throw new ServiceException(ErrorCode.ERROR.getCode(), "移入回收站失败");
            }
        }

        FileRecycleBin rec = new FileRecycleBin();
        rec.setOriginPath(source.toString());
        rec.setTrashPath(trashTarget.toString());
        rec.setFileName(source.getFileName().toString());
        rec.setIsDir(Files.isDirectory(trashTarget) ? 1 : 0);
        rec.setSize(sizeOf(trashTarget));
        rec.setOperator(LoginHelper.getUsername());
        rec.setExpireAt(LocalDateTime.now().plusDays(properties.getTrashRetainDays()));
        recycleMapper.insert(rec);
    }

    /** 分页查询回收站 */
    public PageResult<FileRecycleBin> page(PageQuery query, String keyword) {
        Page<FileRecycleBin> page = recycleMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<FileRecycleBin>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(FileRecycleBin::getFileName, keyword)
                    .or().like(FileRecycleBin::getOriginPath, keyword))
                .orderByDesc(FileRecycleBin::getCreatedAt));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 还原到原路径（目标位置已存在则拒绝） */
    public void restore(Long id) {
        FileRecycleBin rec = requireRecord(id);
        Path origin = Path.of(rec.getOriginPath()).normalize();
        if (!pathGuard.isWithinRoots(origin)) {
            throw new ServiceException(ErrorCode.FILE_PATH_DENIED);
        }
        Path trashPath = Path.of(rec.getTrashPath());
        if (!Files.exists(trashPath)) {
            throw new ServiceException(ErrorCode.FILE_NOT_EXISTS, "回收站文件已丢失");
        }
        if (Files.exists(origin)) {
            throw new ServiceException(ErrorCode.FILE_EXISTS, "原位置已存在同名文件，请先处理");
        }
        try {
            if (origin.getParent() != null) {
                Files.createDirectories(origin.getParent());
            }
            Files.move(trashPath, origin);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "还原失败");
        }
        recycleMapper.deleteById(id);
    }

    /** 彻底删除（物理 + 记录） */
    public void purge(Long id) {
        FileRecycleBin rec = requireRecord(id);
        deleteRecursivelyQuiet(Path.of(rec.getTrashPath()));
        recycleMapper.deleteById(id);
    }

    /** 清空回收站（全部记录与物理文件） */
    public void empty() {
        List<FileRecycleBin> list = recycleMapper.selectList(new LambdaQueryWrapper<>());
        for (FileRecycleBin rec : list) {
            deleteRecursivelyQuiet(Path.of(rec.getTrashPath()));
        }
        recycleMapper.delete(new LambdaQueryWrapper<>());
    }

    /** 每日凌晨清理过期记录（保留天数内不删） */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupExpired() {
        List<FileRecycleBin> expired = recycleMapper.selectList(
            new LambdaQueryWrapper<FileRecycleBin>()
                .lt(FileRecycleBin::getExpireAt, LocalDateTime.now()));
        if (expired.isEmpty()) {
            return;
        }
        log.info("清理过期回收站记录 {} 条", expired.size());
        for (FileRecycleBin rec : expired) {
            deleteRecursivelyQuiet(Path.of(rec.getTrashPath()));
            recycleMapper.deleteById(rec.getId());
        }
    }

    // ==================== 私有工具 ====================

    private FileRecycleBin requireRecord(Long id) {
        FileRecycleBin rec = recycleMapper.selectById(id);
        if (rec == null) {
            throw new ServiceException(ErrorCode.FILE_RECYCLE_NOT_FOUND);
        }
        return rec;
    }

    private boolean isEmptyDir(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "读取目录失败");
        }
    }

    private long sizeOf(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.walk(p)) {
                    return stream.filter(Files::isRegularFile)
                        .mapToLong(path -> {
                            try {
                                return Files.size(path);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
                }
            }
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private void deleteRecursivelyQuiet(Path p) {
        try {
            if (!Files.exists(p)) {
                return;
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.list(p)) {
                    for (Path child : stream.toList()) {
                        deleteRecursivelyQuiet(child);
                    }
                }
                Files.deleteIfExists(p);
            } else {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("物理删除失败: {}", p, e);
        }
    }
}
