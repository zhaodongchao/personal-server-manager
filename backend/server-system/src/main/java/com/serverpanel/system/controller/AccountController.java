package com.serverpanel.system.controller;

import com.serverpanel.common.core.R;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.config.DefaultPreferenceConfig;
import com.serverpanel.system.dto.auth.PasswordBody;
import com.serverpanel.system.dto.auth.PreferencesBody;
import com.serverpanel.system.dto.auth.RouteVO;
import com.serverpanel.system.dto.auth.UserInfoVO;
import com.serverpanel.system.dto.auth.UserProfileBody;
import com.serverpanel.system.entity.mongo.UserPreferenceDocument;
import com.serverpanel.system.service.AuthService;
import com.serverpanel.system.service.PermissionService;
import com.serverpanel.system.service.UserPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户相关：用户信息 / 改密 / 动态菜单 / 偏好设置（Vben Admin 约定路径）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;
    private final PermissionService permissionService;
    private final UserPreferenceService userPreferenceService;

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

    /** 更新当前用户基本资料（个人中心） */
    @PutMapping("/user/profile")
    public R<Void> updateProfile(@Valid @RequestBody UserProfileBody body) {
        authService.updateProfile(body);
        return R.ok();
    }

    /** Vben 后端模式：动态路由树 */
    @GetMapping("/menu/all")
    public R<List<RouteVO>> menus() {
        return R.ok(permissionService.buildUserRoutes(LoginHelper.getUserId()));
    }

    /**
     * 查询当前用户偏好设置（按用户维度，存 MongoDB）。
     * 用户从未配置过时返回后端写死的默认配置。
     */
    @GetMapping("/user/preference")
    public R<Map<String, Object>> preference() {
        String userId = String.valueOf(LoginHelper.getUserId());
        return R.ok(userPreferenceService.getByUserId(userId)
            .map(doc -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("preferences", doc.getPreferences());
                data.put("custom", doc.getCustom());
                return data;
            })
            .orElseGet(() -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("preferences", DefaultPreferenceConfig.getDefaultPreferences());
                data.put("custom", DefaultPreferenceConfig.getDefaultCustom());
                return data;
            }));
    }

    /**
     * 保存当前用户偏好设置（全量覆盖，upsert）
     */
    @PutMapping("/user/preference")
    public R<Void> savePreference(@Valid @RequestBody PreferencesBody body) {
        String userId = String.valueOf(LoginHelper.getUserId());
        userPreferenceService.save(userId, body.getPreferences(), body.getCustom());
        return R.ok();
    }
}
