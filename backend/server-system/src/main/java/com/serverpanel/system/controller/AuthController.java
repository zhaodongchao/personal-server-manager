package com.serverpanel.system.controller;

import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.auth.LoginBody;
import com.serverpanel.system.dto.auth.SafeBody;
import com.serverpanel.system.service.AuthService;
import com.serverpanel.system.service.PermissionService;
import cn.dev33.satoken.stp.StpUtil;
import com.serverpanel.framework.security.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PermissionService permissionService;

    /** 登录（返回 accessToken） */
    @PostMapping("/login")
    public R<Object> login(@Valid @RequestBody LoginBody body, HttpServletRequest request) {
        String token = authService.login(body, request);
        return R.ok(java.util.Map.of("accessToken", token));
    }

    /** 登出 */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /**
     * 二级认证：校验当前登录用户密码，通过后开启安全窗口。
     *
     * <p>刻意<i>不加</i> @Audit —— 审计切面会把入参 JSON 化落库，加在这里等于把明文
     * 口令写进 sys_audit_log。认证事件已由 AuthService 写入 sys_login_log
     * （message 标注「二级认证通过 / 二级认证密码错误」），照样可审计。
     */
    @PostMapping("/safe")
    public R<Object> safe(@Valid @RequestBody SafeBody body, HttpServletRequest request) {
        long timeout = authService.openSafe(body.getPassword(), request);
        return R.ok(java.util.Map.of("timeout", timeout));
    }

    /** Vben 权限码（按钮级 perms） */
    @GetMapping("/codes")
    public R<List<String>> codes() {
        return R.ok(permissionService.getUserPerms(LoginHelper.getUserId()));
    }

    /** 预留：刷新 token（Sa-Token 默认无独立 refresh） */
    @PostMapping("/refresh")
    public R<Object> refresh() {
        StpUtil.renewTimeout(86400);
        return R.ok(java.util.Map.of("accessToken", StpUtil.getTokenValue()));
    }
}
