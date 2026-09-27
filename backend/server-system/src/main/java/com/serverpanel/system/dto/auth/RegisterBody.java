package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册请求（邮箱验证码方式）。
 *
 * <p>注册成功的新账号不分配任何角色（仅个人中心），业务权限由管理员分配；
 * 注册总开关由 serverpanel.register.enabled 控制。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
public class RegisterBody {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,30}$",
            message = "用户名须为 3-30 位字母、数字或下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须为 8-64 位")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    private String code;
}
