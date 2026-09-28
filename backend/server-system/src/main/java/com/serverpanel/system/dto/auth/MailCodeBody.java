package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 邮箱验证码发送请求。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
public class MailCodeBody {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 场景：login=登录、reset=重置密码（要求邮箱已绑定面板账号）；register=注册（要求邮箱未被占用） */
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "login|reset|register", message = "场景仅允许 login / reset / register")
    private String purpose;

    /**
     * 一次性发信令牌（由人机校验 {@code /auth/captcha/click/verify} 签发）。
     * 不发 @NotBlank：缺失时由 AuthService 统一返回 1037 CAPTCHA_REQUIRED，
     * 与「令牌已失效」的 1038 走同一处理口径，前端只需提示「请先完成人机验证」。
     */
    private String captcha;
}
