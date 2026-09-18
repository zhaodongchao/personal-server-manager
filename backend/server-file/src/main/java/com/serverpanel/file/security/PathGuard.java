package com.serverpanel.file.security;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.file.config.FileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件路径安全守卫 —— 文件模块所有路径的统一入口。
 *
 * <p>约束：
 * <ul>
 *   <li>只接受绝对路径，统一 normalize 消除 {@code ../}、{@code .}；</li>
 *   <li>必须位于根目录白名单（{@code serverpanel.file.roots}）之下；</li>
 *   <li>已存在路径用 {@code toRealPath()} 校验（防软链逃逸）；</li>
 *   <li>新路径（创建目标）以其已存在的真实父目录为锚点解析（防中间软链逃逸）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PathGuard {

    private final FileProperties properties;

    /**
     * 解析一个必须已存在的路径（读/删/重命名源等），
     * 归一化 + 白名单 + toRealPath 三重校验。
     */
    public Path resolveExisting(String raw) {
        Path path = normalize(raw);
        ensureWithinRoots(path);
        try {
            Path real = path.toRealPath();
            ensureWithinRoots(real);
            return real;
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.FILE_NOT_EXISTS);
        }
    }

    /**
     * 解析一个创建目标路径（新建/上传/解压目标等），不要求已存在，
     * 但父目录必须已存在且真实位于白名单内。
     */
    public Path resolveNew(String raw) {
        Path path = normalize(raw);
        ensureWithinRoots(path);
        Path parent = path.getParent();
        if (parent != null) {
            Path realParent = resolveExisting(parent.toString());
            return realParent.resolve(path.getFileName());
        }
        return path;
    }

    /** 归一化并确保是白名单内的绝对路径（不触发 IO） */
    public Path normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "路径不能为空");
        }
        Path path = Path.of(raw.trim());
        if (!path.isAbsolute()) {
            path = Path.of("/").resolve(path);
        }
        return path.normalize();
    }

    /** 判断路径（含其真实路径）是否位于任一白名单根目录下 */
    public boolean isWithinRoots(Path path) {
        for (Path root : roots()) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private void ensureWithinRoots(Path path) {
        if (!isWithinRoots(path)) {
            throw new ServiceException(ErrorCode.FILE_PATH_DENIED);
        }
    }

    /** 白名单根目录（real path，目录不存在时以归一化绝对路径兜底） */
    public List<Path> roots() {
        List<Path> list = new ArrayList<>();
        for (String raw : properties.getRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path root = Path.of(raw.trim()).toAbsolutePath().normalize();
            try {
                if (Files.isDirectory(root)) {
                    list.add(root.toRealPath());
                } else {
                    list.add(root);
                }
            } catch (IOException e) {
                list.add(root);
            }
        }
        return list;
    }
}
