package com.serverpanel.system.controller;

import java.util.Map;

import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.auth.MailCodeBody;
import com.serverpanel.system.dto.auth.MailLoginBody;
import com.serverpanel.system.dto.auth.RegisterBody;
import com.serverpanel.system.service.AuthService;
import com.serverpanel.system.service.MailCodeService;
import com.serverpanel.system.service.MailSenderService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * 邮箱验证码认证接口（邮箱登录 / 自助注册）。
 *
 * <p>四个端点均在拦截器白名单内（免登录）：enabled / code / login / register。
 * 发码场景校验与验证码安全（冷却 / 日限 / 一次性消费）由 MailCodeService 承担；
 * 登录与注册的会话建立、登录日志由 AuthService 承担。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class MailAuthController {

    private final AuthService authService;
    private final MailCodeService mailCodeService;
    private final MailSenderService mailSenderService;

    /**
     * 邮箱认证可用性快照：SMTP 是否配置 + 自助注册是否开放 + 允许的邮箱域名白名单。
     *
     * <p>刻意返回多个字段而非合成一个：邮箱登录只依赖 SMTP，注册页却要求二者同时为真
     * （没配 SMTP 就发不出验证码，注册链路必然断在第一步）。合成一个布尔值前端无法区分
     * 「该不该显示注册入口」与「该不该显示邮箱登录入口」。allowedEmailDomains 为空白名单
     * 时返回空数组，表示不限制域名。
     */
    @GetMapping("/mail/enabled")
    public R<Map<String, Object>> enabled() {
        Map<String, Object> result = new java.util.HashMap<>(4);
        result.put("enabled", mailSenderService.enabled());
        result.put("registerEnabled", authService.registerEnabled());
        result.put("allowedEmailDomains", authService.registerAllowedDomains());
        return R.ok(result);
    }

    /**
     * 发送邮箱验证码。安全闸门（人机校验令牌消费 + 来源 IP 限流/封锁）与场景前置校验
     * 均在 AuthService.sendMailCode 内按顺序执行，详见其方法注释。
     */
    @PostMapping("/mail/code")
    public R<Void> code(@Valid @RequestBody MailCodeBody body, HttpServletRequest request) {
        authService.sendMailCode(body, request);
        return R.ok();
    }

    /**
     * 注册用户名实时查重（供前端 onBlur 提示）。
     *
     * <p>仅返回是否可用：格式非法（非 3-30 位字母数字下划线）直接视为不可用，
     * 既不查库也避免被当作枚举入口；最终唯一性仍由注册接口的 uk_username 兜底。
     */
    @GetMapping("/register/check-username")
    public R<Map<String, Boolean>> checkUsername(
            @NotBlank @RequestParam("username") String username) {
        boolean available = username.matches("^[a-zA-Z0-9_]{3,30}$")
                && authService.isUsernameAvailable(username);
        return R.ok(Map.of("available", available));
    }

    /** 邮箱验证码登录（返回 accessToken） */
    @PostMapping("/mail/login")
    public R<Object> login(@Valid @RequestBody MailLoginBody body, HttpServletRequest request) {
        String token = authService.mailLogin(body, request);
        return R.ok(Map.of("accessToken", token));
    }

    /**
     * 自助注册（邮箱验证码方式）。
     *
     * <p>刻意<i>不加</i> @Audit —— 审计切面会把入参 JSON 化落库，注册体含新密码。
     * 注册事件已由 AuthService 写入 sys_login_log（message 标注「自助注册成功」）。
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterBody body, HttpServletRequest request) {
        authService.register(body, request);
        return R.ok();
    }
}
