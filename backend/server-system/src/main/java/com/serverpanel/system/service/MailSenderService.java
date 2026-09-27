package com.serverpanel.system.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.config.MailProperties;

/**
 * 邮件发送服务（邮箱验证码登录 / 注册验证）。
 *
 * <p>多账号支持：{@code serverpanel.mail.accounts} 配置多个发件账号，按 {@code purposes}
 * （register / login / all）路由 —— 同一目的允许多账号时取第一个匹配项，
 * 没有任何匹配时回退到列表首个账号，保证可发出。任一账号可用即视为功能启用。
 *
 * <p>按配置降级：accounts 为空时 {@link #enabled()} 返回 false，对应接口返回 1030，
 * 前端据 {@code /auth/mail/enabled} 隐藏邮箱登录入口，与 OAuth「按配置启用」同一哲学。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Slf4j
@Service
public class MailSenderService {

    /** 构建好的发件账号池（启动期由 MailProperties 装配，运行期不变） */
    private final List<MailAccount> accounts = new ArrayList<>();

    public MailSenderService(MailProperties mailProperties) {
        if (mailProperties.getAccounts() != null) {
            for (MailProperties.Account cfg : mailProperties.getAccounts()) {
                if (!StringUtils.hasText(cfg.getHost())
                        || !StringUtils.hasText(cfg.getUsername())) {
                    // 缺 host / username 的账号视为未配置，跳过（不影响其它账号）
                    log.warn("跳过未完整配置的邮件账号：host={}", cfg.getHost());
                    continue;
                }
                accounts.add(new MailAccount(buildSender(cfg), resolveFrom(cfg), parsePurposes(cfg)));
            }
        }
    }

    /** 邮箱验证码功能是否可用（至少存在一个可发件的账号） */
    public boolean enabled() {
        return !accounts.isEmpty();
    }

    /**
     * 发送纯文本邮件。发送失败向上抛 500（调用方已在发码前落 Redis 限额，
     * 失败不消耗验证码本身 —— 码在发送成功后才写入）。
     *
     * @param purpose 业务目的（register / login），用于选择发件账号
     */
    public void send(String to, String subject, String text, String purpose) {
        MailAccount account = pick(purpose);
        if (account == null) {
            throw new ServiceException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(account.from());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            account.sender().send(message);
        } catch (Exception e) {
            log.warn("Mail send failed: to={}, subject={}, error={}", to, subject, e.getMessage());
            throw new ServiceException(ErrorCode.ERROR, "邮件发送失败，请稍后重试或联系管理员");
        }
    }

    // ===== 内部方法 =====

    /** 按 purpose 选择发件账号：优先 purposes 含该目的或 all；无匹配回退首个账号 */
    private MailAccount pick(String purpose) {
        if (accounts.isEmpty()) {
            return null;
        }
        for (MailAccount a : accounts) {
            if (a.purposes().contains("all") || a.purposes().contains(purpose)) {
                return a;
            }
        }
        return accounts.get(0);
    }

    /** 解析 purposes 为小写集合（默认含 all） */
    private static Set<String> parsePurposes(MailProperties.Account cfg) {
        Set<String> set = new HashSet<>();
        if (!StringUtils.hasText(cfg.getPurposes())) {
            set.add("all");
            return set;
        }
        for (String p : cfg.getPurposes().split(",")) {
            String trimmed = p.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        if (set.isEmpty()) {
            set.add("all");
        }
        return set;
    }

    /** from 缺省时回退 username（多数 SMTP 服务商会校验二者一致） */
    private static String resolveFrom(MailProperties.Account cfg) {
        return StringUtils.hasText(cfg.getFrom()) ? cfg.getFrom() : cfg.getUsername();
    }

    /** 依据配置构建 JavaMailSenderImpl（Spring Boot 单数据源装配不支持多账号，故手动构建） */
    private static JavaMailSender buildSender(MailProperties.Account cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getHost());
        sender.setPort(cfg.getPort());
        sender.setUsername(cfg.getUsername());
        sender.setPassword(cfg.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", String.valueOf(cfg.isSsl()));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        // 587 / STARTTLS 场景：关闭 SSL 直连，启用 STARTTLS
        props.put("mail.smtp.starttls.enable", String.valueOf(!cfg.isSsl()));
        props.put("mail.smtp.starttls.required", String.valueOf(!cfg.isSsl()));
        sender.setJavaMailProperties(props);
        return sender;
    }

    /** 不可变发件账号载体 */
    private record MailAccount(JavaMailSender sender, String from, Set<String> purposes) {}
}
