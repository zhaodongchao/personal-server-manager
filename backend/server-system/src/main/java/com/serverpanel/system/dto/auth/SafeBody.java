package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 二级认证（step-up）请求体。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class SafeBody {

    /** 当前登录用户的登录密码 */
    @NotBlank(message = "密码不能为空")
    private String password;
}
