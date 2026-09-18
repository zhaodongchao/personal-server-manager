package com.serverpanel.system.controller;

import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.auth.LoginBody;
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
