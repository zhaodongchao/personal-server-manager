package com.serverpanel.ops.job;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.common.job.InternalTask;
import com.serverpanel.ops.service.NginxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置任务：手动触发一次 Nginx 证书维护。
 *
 * <p>复用 {@code server-ops} 既有的 {@link NginxService#dailyCertMaintenance()}
 * （当前由 {@code NginxCertRenewScheduler} 每日 03:30 触发）：续期临期证书、
 * 刷新到期状态、临期/过期告警。本类只提供「可手动触发 / 可按自定义 cron 触发」的入口。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NginxCertRenewTask implements InternalTask {

    private final NginxService nginxService;

    @Override
    public String code() {
        return "NGINX_CERT_RENEW";
    }

    @Override
    public String label() {
        return "Nginx 证书续期维护";
    }

    @Override
    public String description() {
        return "续期临期证书、刷新到期状态并告警（等价于面板每日 03:30 的内置维护）";
    }

    @Override
    public Result execute(Map<String, String> params) {
        nginxService.dailyCertMaintenance();
        return Result.ok("Nginx 证书维护已完成");
    }
}
