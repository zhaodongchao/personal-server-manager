package com.serverpanel.system.controller;

import java.util.List;

import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.oauth.OAuthCallbackBody;
import com.serverpanel.system.dto.oauth.OAuthVOs.AuthorizeVO;
import com.serverpanel.system.dto.oauth.OAuthVOs.BindingVO;
import com.serverpanel.system.dto.oauth.OAuthVOs.ProviderVO;
import com.serverpanel.system.service.oauth.OAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第三方登录接口（JustAuth，前端回调方案）。
 *
 * <p>providers / authorize / login 免登录（拦截器白名单，authorize 的
 * intent=bind 在 Service 内显式校验登录态）；bind / bindings / binding
 * 需登录态，由 Sa-Token 拦截器统一拦截。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;

    /** 已启用的第三方平台列表（登录页据此动态渲染图标） */
    @GetMapping("/providers")
    public R<List<ProviderVO>> providers() {
        return R.ok(oauthService.providers());
    }

    /** 生成授权跳转地址；intent=login 登录用，intent=bind 个人中心绑定用（需登录态） */
    @GetMapping("/{provider}/authorize")
    public R<AuthorizeVO> authorize(@PathVariable String provider,
            @RequestParam(defaultValue = "login") String intent, HttpServletRequest request) {
        return R.ok(oauthService.authorize(provider, intent, request));
    }

    /** 第三方登录（回调页提交 code+state 换 accessToken；仅对已绑定用户放行） */
    @PostMapping("/{provider}/login")
    public R<Object> login(@PathVariable String provider,
            @Valid @RequestBody OAuthCallbackBody body, HttpServletRequest request) {
        String token = oauthService.login(provider, body, request);
        return R.ok(oauthService.tokenPayload(token));
    }

    /** 绑定当前登录用户的第三方身份（个人中心发起） */
    @Audit(module = "system", action = "oauth:bind")
    @PostMapping("/{provider}/bind")
    public R<Void> bind(@PathVariable String provider, @Valid @RequestBody OAuthCallbackBody body) {
        oauthService.bind(provider, body);
        return R.ok();
    }

    /** 当前用户已绑定的第三方身份列表 */
    @GetMapping("/bindings")
    public R<List<BindingVO>> bindings() {
        return R.ok(oauthService.bindings());
    }

    /** 解绑当前用户在指定平台的第三方身份 */
    @Audit(module = "system", action = "oauth:unbind", risky = true)
    @DeleteMapping("/{provider}/binding")
    public R<Void> unbind(@PathVariable String provider) {
        oauthService.unbind(provider);
        return R.ok();
    }
}
