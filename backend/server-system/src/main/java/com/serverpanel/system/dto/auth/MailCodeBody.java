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

    /** 场景：login=登录（要求邮箱已绑定面板账号）；register=注册（要求邮箱未被占用） */
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "login|register", message = "场景仅允许 login / register")
    private String purpose;
}
