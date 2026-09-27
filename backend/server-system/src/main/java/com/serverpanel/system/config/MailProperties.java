package com.serverpanel.system.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 邮件发件配置（application.yml 的 {@code serverpanel.mail.*}）。
 *
 * <p>支持多个发件账号，按 {@code purposes}（register / login / all，逗号分隔）路由：
 * 同一目的允许多账号时取第一个匹配项；无任何账号或全不可用则整体降级，
 * 对应 {@code /auth/mail/enabled} 返回 false，前端隐藏邮箱登录与注册入口。
 *
 * <p>首个账号沿用 {@code MAIL_*} / {@code PANEL_MAIL_FROM} 环境变量保持向后兼容；
 * 追加账号用索引环境变量 {@code SERVERPANEL_MAIL_ACCOUNTS_1_HOST} / {@code _PORT} /
 * {@code _USERNAME} / {@code _PASSWORD} / {@code _FROM} / {@code _SSL} / {@code _PURPOSES}。
 *
 * <p>与 {@link OAuthProperties} 同风格：{@code @Component} + {@code @ConfigurationProperties}，
 * 由组件扫描直接注册，供 {@code MailSenderService} 注入构建发件客户端池。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.mail")
public class MailProperties {

    /** 发件账号列表（非空即代表功能可用） */
    private List<Account> accounts = new ArrayList<>();

    /** 验证码有效期（秒） */
    private int codeTtlSeconds = 300;

    /** 单个发件账号配置 */
    @Data
    public static class Account {

        /** SMTP 主机 */
        private String host;

        /** SMTP 端口：465 = SSL 直连，587 = STARTTLS（须 ssl=false） */
        private int port = 465;

        /** SMTP 登录账号（多为完整邮箱） */
        private String username;

        /** SMTP 密码 / 授权码（非邮箱登录密码） */
        private String password;

        /** 发件人地址；多数服务商要求与 username 一致，留空则回退 username */
        private String from;

        /** 是否启用 SSL（465 端口为 true；587 走 STARTTLS 为 false） */
        private boolean ssl = true;

        /** 该账号负责的目的：register / login / all（逗号分隔，默认 all） */
        private String purposes = "all";
    }
}
