package com.serverpanel.system.service.oauth;

import java.util.Arrays;
import java.util.Optional;

/**
 * 支持的第三方登录平台。
 *
 * <p>{@link #key} 为 URL / 配置（application.yml serverpanel.oauth.providers.*）中
 * 的小写标识；数据库 sys_user_oauth.provider 存 {@link #name()}（枚举名，大写）。
 * 声明顺序即前端展示顺序（gitee → github → 钉钉 → 微信 → QQ）。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
public enum OAuthProvider {

    GITEE("gitee", "Gitee"),
    GITHUB("github", "GitHub"),
    DINGTALK("dingtalk", "钉钉"),
    WECHAT_OPEN("wechat", "微信"),
    QQ("qq", "QQ");

    /** URL / 配置中的小写标识 */
    private final String key;

    /** 展示名 */
    private final String displayName;

    OAuthProvider(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 按数据库中的存储值查找（库里存的是枚举名大写形式，如 WECHAT_OPEN）。
     *
     * <p>先按枚举名精确匹配，再回落按小写 key 匹配 —— 两者顺序不能反，
     * 否则 WECHAT_OPEN 会被误当成未知平台，导致前端拿不到正确的图标 key。
     */
    public static Optional<OAuthProvider> ofName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name));
        } catch (IllegalArgumentException e) {
            return ofKey(name);
        }
    }

    /** 按小写 key 查找（找不到返回 empty，由调用方转 1020） */
    public static Optional<OAuthProvider> ofKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equalsIgnoreCase(key)).findFirst();
    }
}
