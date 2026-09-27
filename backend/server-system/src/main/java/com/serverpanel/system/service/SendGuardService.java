package com.serverpanel.system.service;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

/**
 * 发信安全拦截服务（防邮件轰炸 / 账号枚举）。
 *
 * <p>基于来源 IP 的滑动窗口限流 + 超额临时封锁：
 * <ul>
 *   <li>每分钟上限（默认 5）：平滑限流，超出直接拒绝（不封锁，窗口自然回落）；</li>
 *   <li>每小时上限（默认 30）：超出即对该 IP 下发临时封锁标记
 *       （默认 30 分钟），期间所有发码请求返回 {@link ErrorCode#SEND_GUARD_BLOCKED}；</li>
 *   <li>计数在「人机校验通过之后、实际发码之前」执行，保证绕过滑块的脚本也会被限流兜住。</li>
 * </ul>
 *
 * <p>参数全部可在 application.yml（或对应 {@code PANEL_SEND_GUARD_*} 环境变量）调整。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Slf4j
@Service
public class SendGuardService {

    private final StringRedisTemplate redisTemplate;

    @Value("${serverpanel.send-guard.ip-limit-per-minute:5}")
    private int ipLimitPerMinute;

    @Value("${serverpanel.send-guard.ip-limit-per-hour:30}")
    private int ipLimitPerHour;

    @Value("${serverpanel.send-guard.ip-block-minutes:30}")
    private int ipBlockMinutes;

    public SendGuardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 校验并对该 IP 计数 +1。被封锁或超额时抛 {@link ErrorCode#SEND_GUARD_BLOCKED}。
     *
     * @param ip 来源 IP（已处理 X-Forwarded-For 取首个）
     */
    public void checkAndTick(String ip) {
        String blockKey = CacheConstants.SEND_GUARD_IP_BLOCK + ip;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
            long ttl = redisTemplate.getExpire(blockKey);
            throw new ServiceException(ErrorCode.SEND_GUARD_BLOCKED.getCode(),
                    "请求过于频繁，已被临时限制，请 " + Math.max(ttl / 60, 1) + " 分钟后重试");
        }

        // 每分钟窗口
        String minKey = CacheConstants.SEND_GUARD_IP_MIN + ip;
        Long minCount = redisTemplate.opsForValue().increment(minKey);
        if (minCount != null && minCount == 1) {
            redisTemplate.expire(minKey, Duration.ofMinutes(1));
        }
        if (minCount != null && minCount > ipLimitPerMinute) {
            log.debug("Send guard per-minute exceeded for {}: {}", ip, minCount);
            throw new ServiceException(ErrorCode.SEND_GUARD_BLOCKED,
                    "操作过于频繁，请稍后再试");
        }

        // 每小时窗口；超出触发临时封锁
        String hourKey = CacheConstants.SEND_GUARD_IP_HOUR + ip;
        Long hourCount = redisTemplate.opsForValue().increment(hourKey);
        if (hourCount != null && hourCount == 1) {
            redisTemplate.expire(hourKey, Duration.ofHours(1));
        }
        if (hourCount != null && hourCount > ipLimitPerHour) {
            redisTemplate.opsForValue().set(blockKey, "1",
                    Duration.ofMinutes(ipBlockMinutes));
            log.warn("Send guard BLOCKED IP {} for {} min (hour count {})",
                    ip, ipBlockMinutes, hourCount);
            throw new ServiceException(ErrorCode.SEND_GUARD_BLOCKED,
                    "请求过于频繁，已被临时限制，请稍后重试");
        }
    }
}
