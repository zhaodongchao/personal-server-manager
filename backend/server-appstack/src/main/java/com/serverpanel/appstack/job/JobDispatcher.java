package com.serverpanel.appstack.job;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.entity.AppExecutor;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.entity.AppJobLog;
import com.serverpanel.appstack.mapper.AppExecutorMapper;
import com.serverpanel.appstack.mapper.AppJobMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 执行派发器 —— 一次触发从「到点」到「落日志」的完整时序都在这里。
 *
 * <p>时序（设计 §6.2）：
 * <pre>
 * 到点 → 前置检查（状态/执行器/宿主通道，失败即记 trigger_code=500 且不占执行池）
 *      → 取任务级锁（SERIAL 等待 / DISCARD_LATER 丢弃 / COVER_EARLY 覆盖前次）
 *      → 记 RUNNING 日志拿 logId
 *      → 提交虚拟线程池执行，主线程按 timeoutSec 等待
 *      → 回填 handle_* 与最终状态；失败按 retryCount 固定 5s 重试（同一 logId）
 *      → 释放锁
 * </pre>
 *
 * <p><b>调度池与执行池分离</b>（ADR-4）：本类持有独立的虚拟线程执行池，
 * {@link JobScheduler} 的调度线程只负责唤醒与调用 {@link #trigger}，永不阻塞 ——
 * 否则一个 300 秒的备份任务会阻塞面板全部定时任务，而这类缺陷在任务都很短的开发环境
 * 完全不可见。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
public class JobDispatcher {

    /** 失败重试的固定间隔（毫秒），v1 不做指数退避 */
    private static final long RETRY_INTERVAL_MS = 5000L;

    /** 「停止执行」写入终态时的说明（宿主进程不会被跨容器终止，措辞必须诚实） */
    private static final String KILLED_BY_STOP =
            "被手动停止：面板侧执行线程已请求终止；SHELL/SERVICE 的真实执行体在宿主侧，进程可能仍在运行";

    /** 「COVER_EARLY 覆盖」写入终态时的说明 */
    private static final String KILLED_BY_COVER =
            "被 COVER_EARLY 覆盖：面板侧执行线程已请求终止；SHELL/SERVICE 的真实执行体在宿主侧，进程可能仍在运行";

    private final AppJobMapper jobMapper;

    private final AppExecutorMapper executorMapper;

    private final JobLogRecorder recorder;

    private final JobProperties properties;

    private final JobChannel jobChannel;

    private final JobHttpClient httpClient;

    /** 处理器类型 → 实现 */
    private final Map<String, JobHandler> handlers;

    /** 执行池：每个任务一个虚拟线程 */
    private final ExecutorService execPool = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("job-exec-", 0).factory());

    /**
     * 任务级闸门：同一任务不并发，是三种阻塞策略共同的实现基础。
     *
     * <p><b>为什么是 {@link Semaphore} 而不是 {@code ReentrantLock}</b>：闸门在
     * <b>调用线程</b>（HTTP 请求线程 / 调度线程）上获取，却在<b>执行线程</b>
     * （{@code execPool} 的虚拟线程）里释放。{@code ReentrantLock.unlock()} 会校验
     * 「释放者是否就是持有者」，跨线程释放直接抛 {@code IllegalMonitorStateException}；
     * 而这个异常发生在 {@code finally} 里，会被 {@code Future} 静默吞掉，后果是
     * <b>闸门永不释放 —— 同一个任务第二次触发起全部被阻塞策略丢弃，任务实际只跑一次</b>
     * （短任务 + 只跑一次的冒烟测试完全看不出来）。{@code Semaphore} 的
     * acquire / release 不绑定线程，正是这里需要的语义。
     */
    private final Map<Long, Semaphore> locks = new ConcurrentHashMap<>();

    /** 正在执行的实例（供 COVER_EARLY 与「停止执行」使用） */
    private final Map<Long, Running> running = new ConcurrentHashMap<>();

    public JobDispatcher(AppJobMapper jobMapper, AppExecutorMapper executorMapper,
                         JobLogRecorder recorder, JobProperties properties,
                         JobChannel jobChannel, JobHttpClient httpClient,
                         List<JobHandler> handlerBeans) {
        this.jobMapper = jobMapper;
        this.executorMapper = executorMapper;
        this.recorder = recorder;
        this.properties = properties;
        this.jobChannel = jobChannel;
        this.httpClient = httpClient;
        Map<String, JobHandler> map = new LinkedHashMap<>();
        for (JobHandler handler : handlerBeans) {
            map.put(handler.type(), handler);
        }
        this.handlers = Map.copyOf(map);
        log.info("定时任务处理器已装载：{}", this.handlers.keySet());
    }

    /** 已装载的处理器（供 handlers 接口下发 schema） */
    public Map<String, JobHandler> handlers() {
        return handlers;
    }

    /** 当前在跑的任务 → 日志 ID 快照（供列表页展示「运行中」） */
    public Map<Long, Long> runningLogIds() {
        Map<Long, Long> snapshot = new LinkedHashMap<>();
        running.forEach((jobId, state) -> snapshot.put(jobId, state.logId));
        return snapshot;
    }

    // ==================== 触发 ====================

    /**
     * 触发一次执行。本方法只做前置检查、记账与派发，**立即返回**，不等任务跑完。
     *
     * @param jobId       任务 ID
     * @param triggerType {@link JobEnums#TRIGGER_CRON} / {@link JobEnums#TRIGGER_MANUAL}
     * @param override    临时覆盖参数（「立即执行」用，可为 null）
     * @return 本次调度日志 ID；任务已不存在时返回 null
     */
    public Long trigger(Long jobId, String triggerType, Map<String, Object> override) {
        AppJob job = jobMapper.selectById(jobId);
        if (job == null) {
            log.warn("定时任务 {} 已不存在，本次触发终止", jobId);
            return null;
        }
        AppExecutor executor = job.getExecutorId() == null
                ? null : executorMapper.selectById(job.getExecutorId());
        JobHandler handler = handlers.get(job.getHandler());

        String blockReason = preCheck(job, executor, handler, triggerType);
        if (blockReason != null) {
            AppJobLog entry = recorder.recordTrigger(job, executor, triggerType,
                    JobEnums.TRIGGER_FAILED, blockReason, JobEnums.STATUS_DISCARDED);
            writeBack(job, JobEnums.STATUS_DISCARDED, null);
            return entry.getId();
        }

        int timeoutSec = effectiveTimeout(job);
        Semaphore lock = locks.computeIfAbsent(jobId, key -> new Semaphore(1));
        if (!lock.tryAcquire()) {
            String strategy = job.getBlockStrategy() == null
                    ? JobEnums.BLOCK_SERIAL : job.getBlockStrategy();
            if (JobEnums.BLOCK_DISCARD_LATER.equals(strategy)) {
                return discard(job, executor, triggerType, "前次执行未结束，本轮按 DISCARD_LATER 丢弃");
            }
            if (JobEnums.BLOCK_COVER_EARLY.equals(strategy)) {
                String killed = coverEarly(jobId);
                if (!tryLockFor(lock, timeoutSec)) {
                    return discard(job, executor, triggerType,
                            "已覆盖前次（" + killed + "），但本轮仍未取得执行权");
                }
            } else if (!tryLockFor(lock, timeoutSec)) {
                return discard(job, executor, triggerType,
                        "前次执行未结束，等待 " + timeoutSec + " 秒仍未取得执行权");
            }
        }

        AppJobLog entry = recorder.recordTrigger(job, executor, triggerType,
                JobEnums.TRIGGER_DISPATCHED, null, JobEnums.STATUS_RUNNING);
        Running state = new Running(entry.getId());
        running.put(jobId, state);
        Future<?> future = execPool.submit(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                execute(job, executor, handler, entry.getId(), override);
            } catch (RuntimeException e) {
                // 兜底：执行流程本身抛异常时不能让日志永远停在 RUNNING
                log.warn("定时任务「{}」执行流程异常：{}", job.getJobName(), e.getMessage());
                int written = recorder.recordHandle(entry.getId(), false,
                        "执行流程异常：" + e.getMessage(), null,
                        System.currentTimeMillis() - startedAt, JobEnums.STATUS_FAILED, 0);
                if (written > 0) {
                    writeBack(job, JobEnums.STATUS_FAILED, false);
                }
            } finally {
                running.remove(jobId, state);
                lock.release();
            }
        });
        state.future = future;
        writeBack(job, JobEnums.STATUS_RUNNING, null);
        return entry.getId();
    }

    /**
     * 停止该任务正在执行的实例。
     *
     * @return 被终止的日志 ID 与「是否真的终止了线程」；无在跑实例返回 null
     */
    public StopResult stop(Long jobId) {
        Running current = running.get(jobId);
        if (current == null || current.future == null) {
            return null;
        }
        // 顺序很重要：先把日志落定为 KILLED，再中断线程。反过来的话，被中断的执行线程会
        // 抢先回填 FAILED（甚至在写 handle_* 时抛 "Error updating database"），把 KILLED
        // 覆盖掉 —— 用户按下「停止」，日志里看到的却是「失败」。
        recorder.markKilled(current.logId, KILLED_BY_STOP);
        boolean terminated = current.future.cancel(true);
        return new StopResult(current.logId, terminated);
    }

    /** 停止结果 */
    public record StopResult(Long logId, boolean terminated) {}

    // ==================== 前置检查 ====================

    private String preCheck(AppJob job, AppExecutor executor, JobHandler handler, String triggerType) {
        if (handler == null) {
            return "未知的任务处理器：" + job.getHandler();
        }
        // 手动执行允许跑已停用的任务（这正是「我先试一下再启用」的用法）
        if (JobEnums.TRIGGER_CRON.equals(triggerType)
                && (job.getStatus() == null || job.getStatus() != 1)) {
            return "任务已停用";
        }
        if (executor == null) {
            return "执行器不存在（可能已被删除）";
        }
        if (JobEnums.EXEC_DISABLED.equals(executor.getStatus())) {
            return "执行器已停用：" + executor.getAppName();
        }
        if (!JobEnums.TYPE_BUILTIN.equals(executor.getType())
                && JobEnums.EXEC_UNREACHABLE.equals(executor.getStatus())) {
            return "执行器已熔断（连续失败 " + executor.getFailStreak()
                    + " 次），本次不再发起网络请求";
        }
        if (JobEnums.TYPE_BUILTIN.equals(executor.getType())
                && needsHostChannel(job.getHandler()) && !jobChannel.available()) {
            return "宿主通道不可用，无法执行宿主类任务";
        }
        return null;
    }

    private boolean needsHostChannel(String handler) {
        return JobEnums.HANDLER_SHELL.equals(handler) || JobEnums.HANDLER_SERVICE.equals(handler);
    }

    // ==================== 阻塞策略 ====================

    private Long discard(AppJob job, AppExecutor executor, String triggerType, String reason) {
        AppJobLog entry = recorder.recordTrigger(job, executor, triggerType,
                JobEnums.TRIGGER_FAILED, reason, JobEnums.STATUS_DISCARDED);
        writeBack(job, JobEnums.STATUS_DISCARDED, null);
        return entry.getId();
    }

    private boolean tryLockFor(Semaphore lock, int timeoutSec) {
        try {
            return lock.tryAcquire(Math.max(timeoutSec, 1), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * COVER_EARLY：终止前次、放行本轮。
     *
     * <p><b>能力边界（UI 必须明示）</b>：HTTP / 内置任务的面板侧线程可被
     * {@code Future.cancel(true)} 真实中断；SHELL / SERVICE 的真实执行体在宿主机上的
     * {@code host.exec} 子进程里，宿主代理当前**没有**「按 id 终止某次 host.exec」的 op，
     * 因此这里只能逻辑标记为 {@code KILLED}，宿主进程可能继续跑完。
     * 不为它新增宿主 op —— 那会打破本次「宿主零改动」的全部收益（ADR-5）。
     */
    private String coverEarly(Long jobId) {
        Running current = running.get(jobId);
        if (current == null || current.future == null) {
            return "无在跑实例";
        }
        // 同 stop()：先落终态再中断，避免被覆盖前次的日志最终显示成 FAILED
        recorder.markKilled(current.logId, KILLED_BY_COVER);
        current.future.cancel(true);
        return "前次实例已标记 KILLED";
    }

    // ==================== 执行 ====================

    private void execute(AppJob job, AppExecutor executor, JobHandler handler,
                         Long logId, Map<String, Object> override) {
        int timeoutSec = effectiveTimeout(job);
        int maxRetry = clampRetry(job.getRetryCount());
        Map<String, Object> param = JobSupport.toMap(job.getHandlerParam());
        if (override != null && !override.isEmpty()) {
            param.putAll(override);
        }
        JobContext ctx = new JobContext(job, executor, logId, timeoutSec, param,
                message -> log.info("[job:{}] {}", job.getJobName(), message));

        long started = System.currentTimeMillis();
        boolean timedOut = false;
        int attempt = 0;
        JobExecuteResult result;
        while (true) {
            Callable<JobExecuteResult> task = () -> invoke(ctx, handler, executor);
            Future<JobExecuteResult> future = execPool.submit(task);
            try {
                result = future.get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                timedOut = true;
                result = JobExecuteResult.fail("执行超时（超过 " + timeoutSec + " 秒，已请求终止）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                result = JobExecuteResult.fail("执行被中断");
            } catch (ExecutionException e) {
                result = JobExecuteResult.fail("执行异常：" + rootMessage(e));
            }
            if (result.success() || attempt >= maxRetry) {
                break;
            }
            attempt++;
            if (log.isDebugEnabled()) {
                log.debug("定时任务「{}」第 {} 次重试（{}ms 后）", job.getJobName(), attempt, RETRY_INTERVAL_MS);
            }
            sleepQuietly(RETRY_INTERVAL_MS);
        }

        long duration = System.currentTimeMillis() - started;
        String status = result.success()
                ? JobEnums.STATUS_SUCCESS
                : (timedOut ? JobEnums.STATUS_TIMEOUT : JobEnums.STATUS_FAILED);
        int written = recorder.recordHandle(logId, result.success(), result.message(),
                result.output(), duration, status, attempt);
        if (written == 0) {
            // 该日志已被 stop / COVER_EARLY 落定为 KILLED，本次结果属于「已经被终止的那一次」，
            // 不该再回写任务的 lastStatus / failStreak —— 否则一次被终止的执行会把任务
            // 标成失败，还会累积执行器熔断计数。
            log.info("定时任务「{}」的结果未回写：日志 {} 已被终止标记", job.getJobName(), logId);
            return;
        }
        writeBack(job, status, result.success());
        if (!JobEnums.TYPE_BUILTIN.equals(executor.getType())) {
            recordExecutorHealth(executor, result.success(), result.message());
        }
        if (result.success()) {
            log.info("定时任务「{}」执行成功，耗时 {}ms", job.getJobName(), duration);
        } else {
            log.warn("定时任务「{}」执行失败：{}", job.getJobName(), result.message());
        }
    }

    /** 内置执行器走本地 handler；外部执行器按 xxl-job 契约回调其 /run */
    private JobExecuteResult invoke(JobContext ctx, JobHandler handler, AppExecutor executor) {
        if (JobEnums.TYPE_BUILTIN.equals(executor.getType())) {
            return handler.execute(ctx);
        }
        return externalExecute(ctx, executor);
    }

    /**
     * 外部执行器调用（契约沿用 xxl-job 的字段名）。
     *
     * <p>字段名刻意与 xxl-job 一致（{@code executorHandler} / {@code executorParams} /
     * {@code executorBlockStrategy} / {@code executorTimeout} / {@code logId}），
     * 好处是任何现成的 xxl-job 接入文档都能直接套用，降低被管应用的改造理解成本。
     * 差异在于 v1 用**同步响应**回报结果，不引入回调接口（少一份鉴权面）。
     */
    private JobExecuteResult externalExecute(JobContext ctx, AppExecutor executor) {
        String base = executor.getBaseUrl();
        if (base == null || base.isBlank()) {
            return JobExecuteResult.fail("外部执行器未配置 base_url");
        }
        String url = base.endsWith("/") ? base + "run" : base + "/run";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", String.valueOf(ctx.jobId()));
        payload.put("jobName", ctx.jobName());
        payload.put("executorHandler", ctx.job().getHandler());
        payload.put("executorParams", ctx.job().getHandlerParam());
        payload.put("executorBlockStrategy", ctx.job().getBlockStrategy());
        payload.put("executorTimeout", ctx.timeoutSec());
        payload.put("logId", String.valueOf(ctx.logId()));
        payload.put("logDateTime", JobCron.toEpochMilli(LocalDateTime.now()));

        Map<String, String> headers = new LinkedHashMap<>();
        if (executor.getAuthToken() != null && !executor.getAuthToken().isBlank()) {
            headers.put("Authorization", "Bearer " + executor.getAuthToken());
        }
        String body;
        try {
            body = JobSupport.toJson(payload);
        } catch (RuntimeException e) {
            return JobExecuteResult.fail("无法构造执行器请求体：" + e.getMessage());
        }
        try {
            JobHttpClient.Response response = httpClient.send("POST", url, headers,
                    "application/json", body, ctx.timeoutSec());
            String output = "POST " + url + "\nHTTP " + response.status()
                    + "（" + response.durationMs() + "ms）\n"
                    + (response.body() == null ? "" : response.body());
            boolean ok = response.status() >= 200 && response.status() < 300;
            return ok
                    ? JobExecuteResult.ok("执行器返回 HTTP " + response.status(), output)
                    : JobExecuteResult.fail("执行器返回 HTTP " + response.status(), output);
        } catch (RuntimeException e) {
            return JobExecuteResult.fail("调用外部执行器失败：" + e.getMessage());
        }
    }

    /** 外部执行器健康度：失败累计到阈值即熔断，避免每次到点都白等一个超时 */
    private void recordExecutorHealth(AppExecutor executor, boolean success, String message) {
        try {
            AppExecutor patch = new AppExecutor();
            patch.setId(executor.getId());
            if (success) {
                patch.setFailStreak(0);
                patch.setStatus(JobEnums.EXEC_AVAILABLE);
                patch.setLastBeatAt(LocalDateTime.now());
            } else {
                int streak = (executor.getFailStreak() == null ? 0 : executor.getFailStreak()) + 1;
                patch.setFailStreak(streak);
                patch.setLastError(JobSupport.truncate(message, 480));
                if (streak >= JobEnums.EXECUTOR_FAIL_THRESHOLD) {
                    patch.setStatus(JobEnums.EXEC_UNREACHABLE);
                }
            }
            executorMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.warn("回写执行器健康状态失败：{}", e.getMessage());
        }
    }

    // ==================== 任务字段回写 ====================

    private void writeBack(AppJob job, String lastStatus, Boolean success) {
        try {
            AppJob patch = new AppJob();
            patch.setId(job.getId());
            if (JobEnums.STATUS_RUNNING.equals(lastStatus)) {
                patch.setLastFireTime(LocalDateTime.now());
            }
            if (lastStatus != null) {
                patch.setLastStatus(lastStatus);
            }
            if (success != null) {
                patch.setFailStreak(success ? 0
                        : (job.getFailStreak() == null ? 0 : job.getFailStreak()) + 1);
            }
            try {
                patch.setNextFireTime(JobCron.nextFireTime(job.getCronExpr()));
            } catch (RuntimeException ignored) {
                // cron 非法时只回写其它字段，不影响主流程
            }
            jobMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.debug("回写任务字段失败：{}", e.getMessage());
        }
    }

    // ==================== 小工具 ====================

    private int effectiveTimeout(AppJob job) {
        int value = job.getTimeoutSec() == null ? 300 : job.getTimeoutSec();
        return Math.min(Math.max(value, 1), properties.getLimits().getMaxTimeoutSeconds());
    }

    private int clampRetry(Integer retryCount) {
        int value = retryCount == null ? 0 : retryCount;
        return Math.min(Math.max(value, 0), properties.getLimits().getMaxRetryCount());
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 正在执行的实例（future 在提交后才可得，故用可变持有者而非 record） */
    private static final class Running {

        private final Long logId;

        private volatile Future<?> future;

        private Running(Long logId) {
            this.logId = logId;
        }
    }
}
