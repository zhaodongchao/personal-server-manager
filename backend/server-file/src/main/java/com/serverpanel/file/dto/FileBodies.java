package com.serverpanel.file.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求体（Java 21 record + Bean Validation）。
 */
public final class FileBodies {

    private FileBodies() {}

    /** 新建目录 */
    public record MkdirBody(@NotBlank(message = "路径不能为空") String path) {}

    /** 重命名 */
    public record RenameBody(
            @NotBlank(message = "路径不能为空") String path,
            @NotBlank(message = "新名称不能为空") String newName) {}

    /** 移动 */
    public record MoveBody(
            @NotBlank(message = "源路径不能为空") String sourcePath,
            @NotBlank(message = "目标目录不能为空") String targetDir) {}

    /** 在线编辑内容 */
    public record ContentBody(
            @NotBlank(message = "路径不能为空") String path,
            @NotBlank(message = "内容不能为空") String content) {}

    /** 修改权限 */
    public record PermBody(
            @NotBlank(message = "路径不能为空") String path,
            @NotBlank(message = "权限不能为空") String mode) {}

    /** 压缩 */
    public record CompressBody(
            @NotBlank(message = "路径不能为空") String path, String format) {}

    /** 解压 */
    public record ExtractBody(
            @NotBlank(message = "压缩包路径不能为空") String archivePath,
            @NotBlank(message = "目标目录不能为空") String targetDir) {}
}
