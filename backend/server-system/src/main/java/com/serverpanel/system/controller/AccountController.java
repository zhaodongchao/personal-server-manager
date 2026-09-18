package com.serverpanel.system.controller;

import com.serverpanel.common.core.R;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.dto.auth.PasswordBody;
import com.serverpanel.system.dto.auth.RouteVO;
import com.serverpanel.system.dto.auth.UserInfoVO;
import com.serverpanel.system.service.AuthService;
import com.serverpanel.system.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户相关：用户信息 / 改密 / 动态菜单（Vben Admin 约定路径）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;
    private final PermissionService permissionService;

    /** 当前用户信息 */
    @GetMapping("/user/info")
    public R<UserInfoVO> userInfo() {
        return R.ok(authService.getUserInfo());
    }

    /** 修改当前用户密码 */
    @PutMapping("/user/password")
    public R<Void> changePassword(@Valid @RequestBody PasswordBody body) {
        authService.changePassword(body);
        return R.ok();
    }

    /** Vben 后端模式：动态路由树 */
    @GetMapping("/menu/all")
    public R<List<RouteVO>> menus() {
        return R.ok(permissionService.buildUserRoutes(LoginHelper.getUserId()));
    }
}
