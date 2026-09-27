package com.serverpanel.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

/**
 * 邮件发送服务（邮箱验证码登录 / 注册验证）。
 *
 * <p>按配置降级：spring.mail.host 未配置时 Spring Boot 不装配 JavaMailSender，
 * 本服务 {@link #enabled()} 返回 false —— 对应接口返回 1030，
 * 前端据 /auth/mail/enabled 隐藏邮箱登录入口，与 OAuth「按配置启用」同一哲学。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSenderService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** 发件人地址；多数 SMTP 服务商要求与登录账号一致，否则拒发 */
    @Value("${serverpanel.mail.from:}")
    private String from;

    /** 邮箱验证码功能是否可用（SMTP 已装配且发件人已配置） */
    public boolean enabled() {
        return mailSenderProvider.getIfAvailable() != null && from != null && !from.isBlank();
    }

    /**
     * 发送纯文本邮件。发送失败向上抛 500（调用方已在发码前落 Redis 限额，
     * 失败不消耗验证码本身 —— 码在发送成功后才写入）。
     */
    public void send(String to, String subject, String text) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            throw new ServiceException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            sender.send(message);
        } catch (Exception e) {
            log.warn("Mail send failed: to={}, subject={}, error={}", to, subject, e.getMessage());
            throw new ServiceException(ErrorCode.ERROR, "邮件发送失败，请稍后重试或联系管理员");
        }
    }
}
