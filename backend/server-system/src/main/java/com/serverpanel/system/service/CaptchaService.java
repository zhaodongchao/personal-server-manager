package com.serverpanel.system.service;

import java.time.Duration;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

/**
 * 滑块人机校验服务。
 *
 * <p>设计要点（与 OAuth state、登录防爆破同体系的「服务端签发 + 单次消费」令牌）：
 * <ul>
 *   <li>前端滑块拖动完成后，把拖拽时长回传 {@code /auth/captcha/slider/verify}；</li>
 *   <li>服务端校验挑战未过期、未用过、且拖拽时长 {@code >= min-drag-seconds}
 *       （排除「瞬间置位」这类非人类脚本），通过则签发一次性发信令牌；</li>
 *   <li>发信令牌由 {@code /mail/code} 携带并原子消费（GETDEL），保证
 *       「先过人机校验、再发邮件」这一链路不可被绕过或重放。</li>
 * </ul>
 *
 * <p>说明：滑块是交互式人机校验，主要价值在于抬高自动化脚本的门槛；
 * 真正的抗爆破 / 防轰炸由 {@link SendGuardService} 的 IP 限流与封锁承担。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Slf4j
@Service
public class CaptchaService {

    private final StringRedisTemplate redisTemplate;

    @Value("${serverpanel.captcha.slider-ttl-seconds:120}")
    private int sliderTtlSeconds;

    @Value("${serverpanel.captcha.send-token-ttl-seconds:60}")
    private int sendTokenTtlSeconds;

    @Value("${serverpanel.captcha.min-drag-seconds:0.3}")
    private double minDragSeconds;

    public CaptchaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 签发滑块挑战令牌（Redis 中登记，TTL = slider-ttl-seconds） */
    public String issueSliderToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                CacheConstants.CAPTCHA_SLIDER_PREFIX + token, "1",
                Duration.ofSeconds(sliderTtlSeconds));
        return token;
    }

    /**
     * 校验滑块挑战并签发一次性令牌。
     *
     * @param token       前端此前领取的挑战令牌
     * @param dragSeconds 滑块拖拽时长（秒），由前端组件计算回传
     * @param purpose     用途：{@code send}=发邮件验证码（签 captcha:send:），
     *                    {@code login}=密码登录（签 captcha:login:）。两者隔离，
     *                    防止某一场景签发的令牌被重放到另一场景。
     * @return 一次性令牌（TTL = send-token-ttl-seconds）
     */
    public String verifySlider(String token, double dragSeconds, String purpose) {
        String key = CacheConstants.CAPTCHA_SLIDER_PREFIX + token;
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
        // 挑战单次使用：无论后续校验是否通过都先删除，避免被复用
        redisTemplate.delete(key);
        if (dragSeconds < minDragSeconds || dragSeconds > 300) {
            log.debug("Captcha verify rejected: dragSeconds={}", dragSeconds);
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
        String issuedToken = UUID.randomUUID().toString().replace("-", "");
        String prefix = "login".equals(purpose)
                ? CacheConstants.CAPTCHA_LOGIN_PREFIX
                : CacheConstants.CAPTCHA_SEND_PREFIX;
        redisTemplate.opsForValue().set(
                prefix + issuedToken, "1",
                Duration.ofSeconds(sendTokenTtlSeconds));
        return issuedToken;
    }

    /**
     * 消费一次性发信令牌。缺失或已失效（过期 / 已用过）均抛错。
     * 使用 GETDEL 保证原子消费，杜绝并发重放。
     */
    public void consumeSendToken(String sendToken) {
        if (sendToken == null || sendToken.isBlank()) {
            throw new ServiceException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = CacheConstants.CAPTCHA_SEND_PREFIX + sendToken;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /**
     * 消费一次性登录令牌（密码登录场景）。缺失或已失效（过期 / 已用过）均抛错。
     * 使用 GETDEL 保证原子消费，杜绝并发重放；与 {@link #consumeSendToken}
     * 各自消费不同前缀的令牌，跨场景不可混用。
     */
    public void consumeLoginToken(String loginToken) {
        if (loginToken == null || loginToken.isBlank()) {
            throw new ServiceException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = CacheConstants.CAPTCHA_LOGIN_PREFIX + loginToken;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
    }
}
