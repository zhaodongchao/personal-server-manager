package com.serverpanel.system.dto.auth;

import java.util.List;

import lombok.Data;

/**
 * 点选人机校验挑战响应。
 *
 * <p>只包含「前端渲染所需」的信息：图片、要依次点击的目标字符、画布尺寸。
 * 目标字符的<b>坐标答案不下发</b>，由服务端 Redis 侧持有并在校验时比对。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
public class ClickCaptchaVO {

    /** 挑战令牌：校验时原样回传，服务端据此取出该挑战的答案 */
    private String captchaToken;

    /** 验证码图片（data:image/png;base64,...） */
    private String image;

    /** 需按顺序点击的目标字符（顺序即点击顺序） */
    private List<String> prompt;

    /** 图片宽（像素） */
    private int width;

    /** 图片高（像素） */
    private int height;
}
