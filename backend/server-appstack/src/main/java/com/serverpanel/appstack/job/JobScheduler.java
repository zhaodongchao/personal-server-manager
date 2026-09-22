package com.serverpanel.appstack.job;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.mapper.AppJobMapper;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 调度中心 —— 把库里的任务定义变成运行中的调度注册，并在任务变更时热更新。
 *
 * <p>基座是 Spring 自带的 {@link ThreadPoolTaskScheduler} + {@link CronTrigger}，
 * 不引入 Quartz、也不恢复刚被删掉的 cron-utils（ADR-3）。改任务不需要重启面板。
 *
 * <p>启动时机用 {@link ApplicationRunner} 而非 {@code @PostConstruct}：必须等 Flyway
 * 迁移完成、Mapper 可用之后才读库，否则首次部署（表还不存在）会直接启动失败。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler implements ApplicationRunner, DisposableBean {

    private final JobProperties properties;

    private final AppJobMapper jobMapper;

    private final JobDispatcher dispatcher;

    /** 调度线程池（懒建，避免「总开关关闭」时仍占用线程） */
    private ThreadPoolTaskScheduler delegate;

    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getScheduler().isEnabled()) {
            log.warn("定时任务调度器已关闭（serverpanel.job.scheduler.enabled=false），本次不注册任何任务");
            return;
        }
        reloadAll();
    }

    /** 全量重建（启动时、以及排障后手动刷新） */
    public synchronized void reloadAll() {
        cancelAll();
        List<AppJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AppJob>()
                .eq(AppJob::getStatus, 1));
        int registered = 0;
        for (AppJob job : jobs) {
            try {
                schedule(job);
                registered++;
            } catch (RuntimeException e) {
                // 单条脏数据（cron 非法 / 执行器丢失）不得拖垮整个调度器：
                // 记 WARN 并自动停用该任务，否则每次启动都失败，面板永远起不来。
                log.warn("定时任务「{}」(id={}) 注册失败，已自动停用：{}",
                        job.getJobName(), job.getId(), e.getMessage());
                disableDirty(job, e.getMessage());
            }
        }
        log.info("定时任务调度器就绪：启用任务 {} 条，成功注册 {} 条", jobs.size(), registered);
    }

    /** 注册/重注册单个任务（启用、编辑保存后调用） */
    public void refresh(Long jobId) {
        AppJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException(ErrorCode.JOB_NOT_FOUND);
        }
        unregister(jobId);
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        if (job.getStatus() == null || job.getStatus() != 1) {
            return;
        }
        schedule(job);
    }

    /** 取消注册（停用、删除时调用） */
    public void unregister(Long jobId) {
        ScheduledFuture<?> future = futures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 调度器是否处于工作状态 */
    public boolean enabled() {
        return properties.getScheduler().isEnabled();
    }

    /** 下次触发时间（cron 非法时返回 null） */
    public LocalDateTime nextFireTime(AppJob job) {
        try {
            return JobCron.nextFireTime(job.getCronExpr());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void schedule(AppJob job) {
        // 先解析校验，非法时抛出，由调用方决定「跳过 + 停用」
        JobCron.parse(job.getCronExpr());
        CronTrigger trigger = new CronTrigger(job.getCronExpr());
        ScheduledFuture<?> future = scheduler().schedule(() -> {
            try {
                dispatcher.trigger(job.getId(), JobEnums.TRIGGER_CRON, null);
            } catch (RuntimeException e) {
                log.warn("定时任务「{}」触发失败：{}", job.getJobName(), e.getMessage());
            }
        }, trigger);
        futures.put(job.getId(), future);
        writeNextFireTime(job);
    }

    private ThreadPoolTaskScheduler scheduler() {
        if (delegate == null) {
            ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
            created.setPoolSize(Math.max(1, properties.getScheduler().getPoolSize()));
            created.setThreadNamePrefix("job-sched-");
            created.setRemoveOnCancelPolicy(true);
            created.setWaitForTasksToCompleteOnShutdown(false);
            created.initialize();
            delegate = created;
        }
        return delegate;
    }

    private void cancelAll() {
        futures.forEach((jobId, future) -> future.cancel(false));
        futures.clear();
    }

    private void writeNextFireTime(AppJob job) {
        try {
            AppJob patch = new AppJob();
            patch.setId(job.getId());
            patch.setNextFireTime(JobCron.nextFireTime(job.getCronExpr()));
            jobMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.debug("回写下次触发时间失败：{}", e.getMessage());
        }
    }

    private void disableDirty(AppJob job, String reason) {
        try {
            AppJob patch = new AppJob();
            patch.setId(job.getId());
            patch.setStatus(0);
            patch.setLastStatus(JobEnums.STATUS_DISCARDED);
            patch.setJobDesc(JobSupport.truncate(
                    "【已自动停用】" + (reason == null ? "cron 或执行器异常" : reason), 190));
            jobMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.warn("自动停用异常任务失败：{}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        cancelAll();
        if (delegate != null) {
            delegate.shutdown();
        }
    }
}
