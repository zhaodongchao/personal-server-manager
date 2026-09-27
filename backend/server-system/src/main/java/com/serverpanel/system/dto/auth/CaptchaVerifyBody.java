package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 滑块人机校验确认请求。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
public class CaptchaVerifyBody {

    @NotBlank(message = "人机验证挑战不能为空")
    private String captchaToken;

    /** 滑块拖拽时长（秒），由前端组件计算回传；服务端据此排除「瞬间置位」的非人类操作 */
    private double dragSeconds;

    /**
     * 校验用途：{@code send}=发邮件验证码（默认），{@code login}=密码登录。
     * 决定 {@link com.serverpanel.system.service.CaptchaService#verifySlider}
     * 签发何种一次性令牌（captcha:send: / captcha:login:），两场景令牌隔离。
     */
    private String purpose = "send";
}
