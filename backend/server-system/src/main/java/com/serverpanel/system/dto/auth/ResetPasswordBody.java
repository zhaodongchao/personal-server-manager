package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 忘记密码重置请求（邮箱验证码方式）。
 *
 * <p>通过邮箱验证码（purpose=reset，一次性消费）验证持有者对邮箱的控制权后，
 * 直接为该邮箱绑定的账号设置新密码，并强制该账号全端下线。
 *
 * @author zhaodc
 * @since 2026-09-28 UTC+8
 */
@Data
public class ResetPasswordBody {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    private String code;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须为 8-64 位")
    private String password;
}