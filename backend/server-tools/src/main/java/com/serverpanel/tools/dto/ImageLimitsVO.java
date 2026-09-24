package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片转换各项上限：前端据此做入参前置校验与提示。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageLimitsVO {

    /** 单次请求最多文件数 */
    private int maxFiles;

    /** 单个文件最大字节数 */
    private long maxFileBytes;

    /** 单次请求总字节数上限 */
    private long maxTotalBytes;

    /** 缩放后宽/高的最小像素 */
    private int minSide;

    /** 缩放后宽/高的最大像素 */
    private int maxSide;

    /** 质量参数最小值（1-100） */
    private int minQuality;

    /** 质量参数最大值（1-100） */
    private int maxQuality;

    /** 缩放百分比最小值 */
    private int minPercent;

    /** 缩放百分比最大值 */
    private int maxPercent;
}
