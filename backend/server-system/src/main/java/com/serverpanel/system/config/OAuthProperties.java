package com.serverpanel.system.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 第三方登录配置（application.yml 的 serverpanel.oauth.*）。
 *
 * <p>provider 按「配置了凭证才启用」原则工作：client-id 非空白即视为启用，
 * 未配置的平台在 /auth/oauth/providers 接口中不返回，前端也不渲染对应图标 ——
 * 微信 / QQ 等需企业资质的平台，凭证就绪后填入环境变量即可零代码生效。
 *
 * <p>与 {@code MysqlAdminProperties} 同风格：{@code @Component} +
 * {@code @ConfigurationProperties}，由组件扫描直接注册。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.oauth")
public class OAuthProperties {

    /**
     * 统一回调页地址（前端路由）。全平台在开发者后台登记同一个地址：
     * 开发环境 history 模式如 http://localhost:5666/auth/oauth/callback，
     * 生产 hash 模式如 https://面板域名/#/auth/oauth/callback。
     * 一律由后端配置注入，不信任前端传入（防 open redirect）。
     */
    private String redirectUri = "http://localhost:5666/#/auth/oauth/callback";

    /** 授权 state 在 Redis 的有效期（秒），过期后回调须重新发起授权 */
    private long stateTtlSeconds = 300;

    /**
     * 各平台凭证。key 为小写 provider 名（gitee/github/dingtalk/wechat/qq），
     * 与 JustAuth AuthDefaultSource 一一对应；LinkedHashMap 保持 yml 声明顺序。
     */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    /** 单个平台的 OAuth 应用凭证 */
    @Data
    public static class ProviderConfig {

        /** 平台发放的应用 ID；非空白才视为启用 */
        private String clientId = "";

        /** 平台发放的应用密钥（经环境变量注入，禁止明文写死） */
        private String clientSecret = "";

        /** 是否已启用：client-id 非空白即启用（client-secret 缺失会在实际调用时失败） */
        public boolean enabled() {
            return clientId != null && !clientId.isBlank();
        }
    }
}
