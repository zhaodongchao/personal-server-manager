package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个文件的转换结果：成功时给出 dataUrl 与尺寸/大小变化，失败时给出中文原因。
 * 单文件失败不中断批次，由 {@code success=false + error} 表达。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageConvertResultVO {

    /** 原始文件名 */
    private String sourceName;

    /** 探测到的源格式（如 JPEG；无法识别时为 null） */
    private String sourceFormat;

    /** 目标格式（规范大写） */
    private String targetFormat;

    /** 转换后输出文件名（原名 + 新扩展名） */
    private String outputName;

    /** 原始字节数 */
    private long sizeBefore;

    /** 转换后字节数 */
    private long sizeAfter;

    /** 转换后宽度（像素） */
    private int width;

    /** 转换后高度（像素） */
    private int height;

    /** 是否实际执行了缩放（false 表示保持原尺寸） */
    private boolean resized;

    /** 转换后图片 dataUrl（base64，前端可直接预览与下载） */
    private String dataUrl;

    /** 是否成功 */
    private boolean success;

    /** 失败时的中文原因（成功时为 null） */
    private String error;
}
