package com.serverpanel.ops.schedule;

import com.serverpanel.ops.service.NginxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nginx 证书每日维护调度：续期临期证书、刷新到期状态、临期/过期告警。
 *
 * <p>每日 03:30 执行（Cron：秒 分 时 日 月 周）。失败时仅记录日志，不影响其它实例/证书。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NginxCertRenewScheduler {

    private final NginxService nginxService;

    @Scheduled(cron = "0 30 3 * * ?")
    public void run() {
        try {
            nginxService.dailyCertMaintenance();
        } catch (RuntimeException e) {
            log.warn("Nginx 证书每日维护调度异常: {}", e.getMessage());
        }
    }
}
