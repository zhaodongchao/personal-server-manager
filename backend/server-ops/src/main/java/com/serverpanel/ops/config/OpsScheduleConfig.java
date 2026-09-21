package com.serverpanel.ops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运维模块定时任务开关（每日证书续期 + 到期告警）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Configuration
@EnableScheduling
public class OpsScheduleConfig {
}
