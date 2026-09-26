package com.serverpanel.system.dto.oauth;

import jakarta.validation.constraints.NotBlank;

/**
 * OAuth 回调请求（登录/绑定共用）：前端回调页从 URL query 取出 code 与 state 提交。
 *
 * @param code  平台发放的一次性授权码
 * @param state 授权发起时后端生成并存 Redis 的防伪串（intent:provider:random）
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
public record OAuthCallbackBody(
        @NotBlank(message = "授权码不能为空") String code,
        @NotBlank(message = "授权状态不能为空") String state) {}
