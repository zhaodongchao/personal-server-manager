package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片格式描述：供前端下拉选择与参数联动展示。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageFormatVO {

    /** 格式名（规范大写，如 JPEG / PNG / WEBP / BMP / GIF / TIFF，转换请求直接回传该值） */
    private String format;

    /** 常用文件扩展名 */
    private String ext;

    /** MIME 类型（用于 dataUrl 与下载） */
    private String mime;

    /** 是否无损格式（无损格式的质量参数不生效） */
    private boolean lossless;

    /** 是否支持质量参数（JPEG/WEBP 支持，PNG/BMP/GIF 不支持，TIFF 部分压缩下支持） */
    private boolean qualitySupported;

    /** 是否支持透明通道（JPEG/BMP 不支持，写入时自动白底拍平） */
    private boolean alphaSupported;

    /** 行为说明（如 GIF 动图转出为静帧） */
    private String note;
}
