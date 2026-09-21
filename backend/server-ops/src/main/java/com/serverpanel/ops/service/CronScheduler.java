package com.serverpanel.ops.service;

import com.serverpanel.ops.entity.OpsCronJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 计划任务调度器：周期扫描到期任务并执行，另按日清理执行日志。
 *
 * <p>面板内自研调度（而非写 crontab），理由：不依赖系统 crond、可记录执行日志、
 * 命令经白名单校验，且能在宿主机上执行（容器里没有系统命令）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronScheduler {

    private static final long SCAN_INTERVAL_MS = 15_000L;

    private final CronJobService cronJobService;

    /** 执行日志保留天数 */
    @Value("${serverpanel.ops.cron.log-retain-days:30}")
    private int logRetainDays;

    /** 每个任务最多保留的执行日志条数 */
    @Value("${serverpanel.ops.cron.log-max-per-job:500}")
    private int logMaxPerJob;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        for (OpsCronJob job : cronJobService.dueJobs(now)) {
            try {
                handleDue(job, now);
            } catch (Exception e) {
                log.warn("计划任务 {} 调度失败: {}", job.getId(), e.getMessage());
            }
        }
    }

    /**
     * 处理一个到期任务：先判定是否为「错过的执行」，再按 misfire 策略决定是否补跑。
     *
     * <p>为什么必须先判 misfire：面板停机（发版、宿主重启）期间到期的任务，
     * 若不做区分，恢复后会被当成「正常到期」全部补跑一遍；
     * 一个每分钟的任务停机一小时就会瞬间发起 60 次执行——即「补跑风暴」。
     */
    private void handleDue(OpsCronJob job, LocalDateTime now) {
        LocalDateTime next = job.getNextRunAt();
        if (next == null) {
            // 启用但没算过下次时间（如刚由停用改为启用）：先补算，下一轮再执行
            cronJobService.advanceNextRun(job);
            return;
        }
        boolean missed = Duration.between(next, now).toSeconds()
                > CronJobService.MISFIRE_THRESHOLD_SEC;
        String misfire = CronJobService.misfirePolicyOf(job);

        if (missed && CronJobService.MISFIRE_SKIP.equals(misfire)) {
            log.info("计划任务 [{}] 错过了 {} 处的执行（策略=skip），直接推进到未来下一次",
                    job.getName(), next);
            cronJobService.advanceNextRun(job);
            return;
        }

        int runs = 1;
        if (missed && CronJobService.MISFIRE_CATCH_UP.equals(misfire)) {
            int missedCount = cronJobService.countMissed(job, CronJobService.MAX_CATCH_UP + 1);
            if (missedCount > CronJobService.MAX_CATCH_UP) {
                log.warn("计划任务 [{}] 错过的执行达 {} 次，超过上限，本次只补跑 {} 次",
                        job.getName(), missedCount, CronJobService.MAX_CATCH_UP);
                missedCount = CronJobService.MAX_CATCH_UP;
            }
            runs = Math.max(1, missedCount);
        }

        for (int i = 0; i < runs; i++) {
            String trigger = missed ? CronJobService.TRIGGER_RETRY : CronJobService.TRIGGER_CRON;
            Long logId = cronJobService.executeJob(job, trigger, null);
            if (i < runs - 1
                    && !cronJobService.awaitFinish(logId, cronJobService.timeoutSeconds(job))) {
                log.warn("计划任务 [{}] 补跑等待超时，中止剩余 {} 次补跑", job.getName(), runs - 1 - i);
                break;
            }
        }
        cronJobService.advanceNextRun(job);
    }

    /** 每日 03:30 清理执行日志，避免 ops_cron_log 只增不减地膨胀 */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        try {
            cronJobService.cleanupLogs(logRetainDays, logMaxPerJob);
        } catch (Exception e) {
            log.warn("计划任务日志清理失败: {}", e.getMessage());
        }
    }
}
