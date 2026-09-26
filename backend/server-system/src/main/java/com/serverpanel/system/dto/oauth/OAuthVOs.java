package com.serverpanel.system.dto.oauth;

import java.time.LocalDateTime;

/**
 * OAuth 出参集合。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
public final class OAuthVOs {

    private OAuthVOs() {}

    /** 已启用的第三方平台（GET /auth/oauth/providers） */
    public record ProviderVO(String provider, String name) {}

    /** 授权跳转地址（GET /auth/oauth/{provider}/authorize） */
    public record AuthorizeVO(String authorizeUrl) {}

    /** 当前用户已绑定列表（GET /auth/oauth/bindings） */
    public record BindingVO(String provider, String name, String nickname, String avatar,
            LocalDateTime boundAt) {}
}
