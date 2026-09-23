package com.serverpanel.tools.dto;

import lombok.Data;

/**
 * 二维码生成结果。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class QrcodeGenerateResultVO {

    /** 实际编码进二维码的文本（拼装后的结果，便于用户核对） */
    private String content;

    /** 格式为 data URL 的图片，可直接赋给 <img src> */
    private String dataUrl;

    /** 输出格式：PNG / JPEG */
    private String format;

    /** 目标边长 */
    private int requestSize;

    /**
     * 实际边长：模块数的整数倍。
     *
     * <p>刻意不把用户想要的任意边长硬拉伸到非整数倍 —— 那会让每个码点宽窄不一，
     * 密集内容下反而扫不出来。返回真实尺寸让前端如实展示。
     */
    private int realSize;

    /** 每个模块的像素数 = realSize / moduleCount */
    private int scale;

    /** 含静默区的总模块数 */
    private int moduleCount;

    /** 图片字节数 */
    private int bytes;
}
