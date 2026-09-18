package com.serverpanel.ops.service;

import com.serverpanel.ops.entity.OpsCronJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划任务调度器：周期扫描到期任务并异步执行。
 *
 * <p>面板内自研调度（而非写 crontab），理由：
 * 不依赖系统 crond、可记录执行日志、命令经白名单校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronScheduler {

    private static final long SCAN_INTERVAL_MS = 15_000L;

    private final CronJobService cronJobService;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        List<OpsCronJob> due = cronJobService.dueJobs(now);
        if (due.isEmpty()) {
            return;
        }
        for (OpsCronJob job : due) {
            try {
                cronJobService.executeJob(job);
            } catch (Exception e) {
                log.warn("cron job {} execute failed: {}", job.getId(), e.getMessage());
            }
            try {
                cronJobService.advanceNextRun(job);
            } catch (Exception e) {
                log.warn("cron job {} next-run compute failed: {}", job.getId(), e.getMessage());
            }
        }
    }
}
