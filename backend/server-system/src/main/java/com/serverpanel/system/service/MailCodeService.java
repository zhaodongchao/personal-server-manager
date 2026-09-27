package com.serverpanel.system.service;

import java.security.SecureRandom;
import java.time.Duration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

/**
 * 邮箱验证码服务：生成 / 发送 / 校验。
 *
 * <p>安全设计（与 OAuth state、登录防爆破同体系的 Redis 限流）：
 * <ul>
 *   <li>6 位数字码，TTL 默认 300 秒，登录与注册场景的码按 purpose 隔离、互不通用；</li>
 *   <li>同邮箱同场景 60 秒冷却；每邮箱每 24 小时滑动窗口最多 10 条（防轰炸）；</li>
 *   <li>校验失败 5 次作废当前码；校验成功即删除（一次性消费，防重放）；</li>
 *   <li>验证码在「邮件发送成功后」才写入 Redis —— 发送失败不占用冷却与日限额之外的任何状态。</li>
 * </ul>
 *
 * <p>注意：冷却与日限额在发送前检查（无论发送成败都会消耗冷却窗口），
 * 这是刻意的 —— 失败重试也必须等待冷却，避免发码接口被高频打。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Service
@RequiredArgsConstructor
public class MailCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_LENGTH = 6;
    private static final int MAX_VERIFY_FAIL = 5;
    private static final int DAILY_LIMIT = 10;

    private final StringRedisTemplate redisTemplate;
    private final MailSenderService mailSenderService;

    /** 验证码有效期（秒），下限 60 秒在发送时钳制 */
    @Value("${serverpanel.mail.code-ttl-seconds:300}")
    private int codeTtlSeconds;

    /** 发送验证码：冷却 / 日限检查 → 生成 → 发邮件 → 落 Redis */
    public void send(String email, String purpose) {
        if (!mailSenderService.enabled()) {
            throw new ServiceException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        String codeKey = codeKey(email, purpose);
        String cooldownKey = CacheConstants.MAIL_COOLDOWN_PREFIX + purpose + ":" + email;
        String dailyKey = CacheConstants.MAIL_DAILY_PREFIX + email;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new ServiceException(ErrorCode.MAIL_SEND_TOO_FREQUENT);
        }
        String daily = redisTemplate.opsForValue().get(dailyKey);
        if (daily != null && Integer.parseInt(daily) >= DAILY_LIMIT) {
            throw new ServiceException(ErrorCode.MAIL_DAILY_LIMIT_EXCEEDED);
        }

        String code = generateCode();
        mailSenderService.send(email, subjectOf(purpose), textOf(code, purpose), purpose);

        // 发送成功才落状态：旧码覆盖（新码生效旧码作废）、冷却 60s、日计数 +1（首条起 24h 窗口）
        redisTemplate.opsForValue().set(codeKey, code,
                Duration.ofSeconds(Math.max(codeTtlSeconds, 60)));
        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(60));
        Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
        if (dailyCount != null && dailyCount == 1) {
            redisTemplate.expire(dailyKey, Duration.ofHours(24));
        }
    }

    /**
     * 校验验证码：成功即删除（一次性消费）并清失败计数；
     * 失败累计 {@value #MAX_VERIFY_FAIL} 次作废当前码。
     */
    public void verify(String email, String purpose, String code) {
        String codeKey = codeKey(email, purpose);
        String failKey = CacheConstants.MAIL_FAIL_PREFIX + purpose + ":" + email;
        String stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            throw new ServiceException(ErrorCode.MAIL_CODE_INVALID);
        }
        if (!stored.equals(code)) {
            Long fails = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, Duration.ofSeconds(Math.max(codeTtlSeconds, 60)));
            if (fails != null && fails >= MAX_VERIFY_FAIL) {
                // 失败次数达上限：作废当前码与计数，逼迫重新发码（发码本身有冷却与日限约束）
                redisTemplate.delete(codeKey);
                redisTemplate.delete(failKey);
            }
            throw new ServiceException(ErrorCode.MAIL_CODE_INVALID);
        }
        redisTemplate.delete(codeKey);
        redisTemplate.delete(failKey);
    }

    // ===== 内部方法 =====

    private String codeKey(String email, String purpose) {
        return CacheConstants.MAIL_CODE_PREFIX + purpose + ":" + email;
    }

    /** 6 位数字码，首位允许 0（左补零），统一格式为定长字符串 */
    private String generateCode() {
        return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(1_000_000));
    }

    private String subjectOf(String purpose) {
        return "login".equals(purpose) ? "ServerPanel 登录验证码" : "ServerPanel 注册验证码";
    }

    private String textOf(String code, String purpose) {
        String action = "login".equals(purpose) ? "登录" : "注册";
        long minutes = Math.max(Math.max(codeTtlSeconds, 60) / 60, 1);
        return ("您正在使用邮箱验证码%s ServerPanel，验证码：%s%n%n"
                + "验证码 %d 分钟内有效，且仅可使用一次。%n"
                + "若非本人操作，请忽略本邮件，他人可能在尝试%s您的账号。")
                .formatted(action, code, minutes, action);
    }
}
