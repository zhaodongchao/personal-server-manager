package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 */
@Data
public class LoginBody {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 人机校验登录令牌（滑块校验通过后签发，一次性、TTL 60s）。
     * 不强制 @NotBlank：缺失时由 AuthService 统一返回 1037 CAPTCHA_REQUIRED，
     * 与「令牌已失效」的 1038 走同一处理口径。
     */
    private String captcha;
}
