package com.serverpanel.file.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.file.dto.FileEntry;
import com.serverpanel.file.security.PathGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 文件管理器核心服务（全部路径经 PathGuard 安全校验）。
 *
 * <p>能力：列表/新建/重命名/移动/上传/下载/在线编辑/权限/压缩解压；
 * 删除与回收站见 {@link RecycleService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    /** 在线编辑文本上限 2MB */
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    /** 常见文本扩展名（判定失败的兜底） */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".txt", ".log", ".md", ".markdown", ".json", ".yml", ".yaml", ".xml", ".html",
        ".htm", ".css", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".vue", ".java", ".kt",
        ".py", ".sh", ".bash", ".zsh", ".conf", ".ini", ".properties", ".sql", ".c",
        ".h", ".cpp", ".hpp", ".go", ".rs", ".php", ".rb", ".pl", ".toml", ".csv",
        ".env", ".gitignore", ".gitattributes", ".dockerfile", ".editorconfig", ".svg");

    private final PathGuard pathGuard;

    // ==================== 查询 ====================

    /** 白名单根目录（前端“根目录”选择器） */
    public List<FileEntry> roots() {
        return pathGuard.roots().stream().map(this::toEntry).toList();
    }

    /** 目录内容（目录在前，按名称升序） */
    public List<FileEntry> list(String rawPath) {
        Path dir = pathGuard.resolveExisting(rawPath);
        if (!Files.isDirectory(dir)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "路径不是目录");
        }
        try (var stream = Files.list(dir)) {
            return stream
                .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString()))
                .map(this::toEntry)
                .toList();
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "读取目录失败: " + e.getMessage());
        }
    }

    /** 在线编辑内容（文本文件，≤2MB） */
    public String content(String rawPath) {
        Path source = pathGuard.resolveExisting(rawPath);
        requireTextFile(source);
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "读取文件失败");
        }
    }

    /** 下载（流式） */
    public ResponseEntity<Resource> download(String rawPath) {
        Path source = pathGuard.resolveExisting(rawPath);
        if (!Files.isRegularFile(source)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "仅支持下载文件");
        }
        Resource resource = new FileSystemResource(source);
        String encoded = URLEncoder.encode(source.getFileName().toString(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        try {
            builder.contentLength(resource.contentLength());
        } catch (IOException ignore) {
            // 长度未知时省略 Content-Length，浏览器仍可正常下载
        }
        return builder.body(resource);
    }

    // ==================== 写操作 ====================

    /** 新建目录（父目录须已存在） */
    public void mkdir(String rawPath) {
        Path target = pathGuard.resolveNew(rawPath);
        try {
            Files.createDirectories(target);
        } catch (FileAlreadyExistsException e) {
            throw new ServiceException(ErrorCode.FILE_EXISTS);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "创建目录失败");
        }
    }

    /** 重命名 */
    public void rename(String rawPath, String newName) {
        if (newName == null || newName.isBlank() || newName.contains("/")
            || newName.equals(".") || newName.equals("..")) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非法文件名");
        }
        Path source = pathGuard.resolveExisting(rawPath);
        if (source.getParent() == null) {
            throw new ServiceException(ErrorCode.FILE_ROOT_FORBIDDEN);
        }
        Path target = pathGuard.resolveNew(source.getParent().resolve(newName).toString());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            throw new ServiceException(ErrorCode.FILE_EXISTS);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "重命名失败");
        }
    }

    /** 移动（跨目录，目标须为已存在目录） */
    public void move(String sourcePath, String targetDir) {
        Path source = pathGuard.resolveExisting(sourcePath);
        Path targetDirP = pathGuard.resolveExisting(targetDir);
        if (!Files.isDirectory(targetDirP)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "目标不是目录");
        }
        if (targetDirP.startsWith(source)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "不能移动到自身内部");
        }
        Path target = pathGuard.resolveNew(targetDirP.resolve(source.getFileName()).toString());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            throw new ServiceException(ErrorCode.FILE_EXISTS);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "移动失败");
        }
    }

    /** 上传（可多文件），返回成功数 */
    public int upload(String rawDir, MultipartFile[] files) {
        Path dir = pathGuard.resolveExisting(rawDir);
        if (!Files.isDirectory(dir)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "目标不是目录");
        }
        int count = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String name = sanitizeFileName(file.getOriginalFilename());
            Path target = pathGuard.resolveNew(dir.resolve(name).toString());
            if (Files.exists(target)) {
                throw new ServiceException(ErrorCode.FILE_EXISTS, "已存在同名文件: " + name);
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
                count++;
            } catch (IOException e) {
                throw new ServiceException(ErrorCode.ERROR.getCode(), "上传失败: " + name);
            }
        }
        return count;
    }

    /** 在线编辑保存 */
    public void updateContent(String rawPath, String content) {
        Path target = pathGuard.resolveExisting(rawPath);
        requireTextFile(target);
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "保存文件失败");
        }
    }

    /** 修改权限（模式支持 755 / rwxr-xr-x） */
    public void perm(String rawPath, String mode) {
        Path target = pathGuard.resolveExisting(rawPath);
        Set<PosixFilePermission> perms = parseMode(mode);
        try {
            Files.setPosixFilePermissions(target, perms);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "修改权限失败");
        }
    }

    // ==================== 压缩 / 解压 ====================

    /** 压缩（zip / tar.gz），产物生成在源同目录 */
    public void compress(String rawPath, String format) {
        Path source = pathGuard.resolveExisting(rawPath);
        String fmt = (format == null || format.isBlank()) ? "zip" : format.trim().toLowerCase();
        Path archive = switch (fmt) {
            case "zip" -> source.resolveSibling(source.getFileName() + ".zip");
            case "tar.gz", "tgz" -> source.resolveSibling(source.getFileName() + ".tar.gz");
            default -> throw new ServiceException(ErrorCode.FILE_ARCHIVE_UNSUPPORTED);
        };
        archive = pathGuard.resolveNew(archive.toString());
        try {
            if (fmt.equals("zip")) {
                zip(source, archive);
            } else {
                tarGz(source, archive);
            }
        } catch (IOException e) {
            deleteQuietly(archive);
            throw new ServiceException(ErrorCode.ERROR.getCode(), "压缩失败: " + e.getMessage());
        }
    }

    /** 解压到目标目录（zip / tar.gz），含 zip-slip 防护 */
    public void extract(String archivePath, String targetDir) {
        Path archive = pathGuard.resolveExisting(archivePath);
        if (!Files.isRegularFile(archive)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "压缩包不存在");
        }
        Path target = pathGuard.resolveExisting(targetDir);
        if (!Files.isDirectory(target)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "目标不是目录");
        }
        String name = archive.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".zip")) {
                extractZip(archive, target);
            } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                extractTarGz(archive, target);
            } else {
                throw new ServiceException(ErrorCode.FILE_ARCHIVE_UNSUPPORTED);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "解压失败: " + e.getMessage());
        }
    }

    // ==================== 私有工具 ====================

    private FileEntry toEntry(Path p) {
        boolean isDir = Files.isDirectory(p);
        long size = 0;
        long lastModified = 0;
        String perms = null;
        try {
            if (!isDir) {
                size = Files.size(p);
            }
            lastModified = Files.getLastModifiedTime(p).toMillis();
            perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
        } catch (IOException | UnsupportedOperationException ignore) {
            // 权限读取失败不影响列表（如特殊文件系统）
        }
        return new FileEntry(p.getFileName().toString(), p.toString(), isDir,
            size, lastModified, perms, !isDir && isTextFile(p));
    }

    private boolean isTextFile(Path p) {
        try {
            if (Files.size(p) > MAX_TEXT_BYTES) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot))) {
            return true;
        }
        // 无 NUL 字节即视为文本
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void requireTextFile(Path p) {
        if (!Files.isRegularFile(p)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "仅支持文件操作");
        }
        try {
            if (Files.size(p) > MAX_TEXT_BYTES) {
                throw new ServiceException(ErrorCode.FILE_TOO_LARGE_TEXT);
            }
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "读取文件大小失败");
        }
        if (!isTextFile(p)) {
            throw new ServiceException(ErrorCode.FILE_NOT_TEXT);
        }
    }

    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "文件名不能为空");
        }
        // 只保留 basename，去除任何路径成分
        String name = original.replace('\\', '/');
        int idx = name.lastIndexOf('/');
        String base = idx >= 0 ? name.substring(idx + 1) : name;
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非法文件名");
        }
        return base;
    }

    private Set<PosixFilePermission> parseMode(String mode) {
        if (mode == null) {
            throw new ServiceException(ErrorCode.FILE_PERM_INVALID);
        }
        String m = mode.trim();
        if (m.matches("[0-7]{3}")) {
            int oct = Integer.parseInt(m, 8);
            Set<PosixFilePermission> perms = new java.util.HashSet<>();
            if ((oct & 0b100000000) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((oct & 0b010000000) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((oct & 0b001000000) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((oct & 0b000100000) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((oct & 0b000010000) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((oct & 0b000001000) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((oct & 0b000000100) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((oct & 0b000000010) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((oct & 0b000000001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            return perms;
        }
        if (m.matches("[rwx-]{9}")) {
            return PosixFilePermissions.fromString(m);
        }
        throw new ServiceException(ErrorCode.FILE_PERM_INVALID);
    }

    private void zip(Path source, Path archive) throws IOException {
        Path base = source.getParent() == null ? source : source.getParent();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (!dir.equals(base)) {
                        zos.putNextEntry(new ZipEntry(
                            base.relativize(dir).toString().replace('\\', '/') + "/"));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    zos.putNextEntry(new ZipEntry(
                        base.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void tarGz(Path source, Path archive) throws IOException {
        Path base = source.getParent() == null ? source : source.getParent();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(Files.newOutputStream(archive)), "UTF-8")) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (!dir.equals(base)) {
                        tos.putArchiveEntry(new TarArchiveEntry(
                            base.relativize(dir).toString().replace('\\', '/') + "/"));
                        tos.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    TarArchiveEntry entry = new TarArchiveEntry(file.toFile(),
                        base.relativize(file).toString().replace('\\', '/'));
                    entry.setSize(attrs.size());
                    tos.putArchiveEntry(entry);
                    Files.copy(file, tos);
                    tos.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
            tos.finish();
        }
    }

    private void extractZip(Path archive, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = safeResolve(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void extractTarGz(Path archive, Path target) throws IOException {
        try (TarArchiveInputStream tis = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                Path out = safeResolve(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(tis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** zip-slip 防护：解压条目必须落在目标目录内 */
    private Path safeResolve(Path target, String entryName) throws IOException {
        Path targetReal = target.toRealPath();
        Path resolved = targetReal.resolve(entryName).normalize();
        if (!resolved.startsWith(targetReal)) {
            throw new ServiceException(ErrorCode.FILE_PATH_DENIED, "压缩包内含非法路径: " + entryName);
        }
        return resolved;
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
            // ignore
        }
    }
}
