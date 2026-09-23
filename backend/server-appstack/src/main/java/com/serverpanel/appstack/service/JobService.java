package com.serverpanel.appstack.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.dto.CronPreviewVO;
import com.serverpanel.appstack.dto.ExecutorBody;
import com.serverpanel.appstack.dto.ExecutorVO;
import com.serverpanel.appstack.dto.JobBody;
import com.serverpanel.appstack.dto.JobQuery;
import com.serverpanel.appstack.entity.AppExecutor;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.entity.AppJobLog;
import com.serverpanel.appstack.job.JobChannel;
import com.serverpanel.appstack.job.JobCron;
import com.serverpanel.appstack.job.JobDispatcher;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.job.JobHttpClient;
import com.serverpanel.appstack.job.JobScheduler;
import com.serverpanel.appstack.job.JobSupport;
import com.serverpanel.appstack.job.internal.InternalTaskRegistry;
import com.serverpanel.appstack.mapper.AppExecutorMapper;
import com.serverpanel.appstack.mapper.AppJobLogMapper;
import com.serverpanel.appstack.mapper.AppJobMapper;
import com.serverpanel.common.constant.ProtectedUnits;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务业务服务：任务的增删改查、调度联动、cron 预览、执行器管理。
 *
 * <p>校验顺序（任一步失败即拒绝，不做部分保存）：唯一性 → cron → 执行器 →
 * 处理器参数（按 handler 各自 schema，错误码按语义区分）→ 超时/重试/策略 →
 * 落库 → 按 status 决定注册或取消注册。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppJobMapper jobMapper;

    private final AppExecutorMapper executorMapper;

    private final AppJobLogMapper logMapper;

    private final JobScheduler scheduler;

    private final JobDispatcher dispatcher;

    private final JobProperties properties;

    private final JobHttpClient httpClient;

    private final InternalTaskRegistry internalTaskRegistry;

    private final CommandExecutor commandExecutor;

    private final JobChannel jobChannel;

    // ==================== 任务查询 ====================

    public PageResult<AppJob> page(JobQuery query) {
        Page<AppJob> page = jobMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<AppJob>()
                        .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                                wrapper -> wrapper.like(AppJob::getJobName, query.getKeyword())
                                        .or().like(AppJob::getJobDesc, query.getKeyword()))
                        .eq(query.getHandler() != null && !query.getHandler().isBlank(),
                                AppJob::getHandler, query.getHandler())
                        .eq(query.getExecutorId() != null,
                                AppJob::getExecutorId, query.getExecutorId())
                        .eq(query.getStatus() != null, AppJob::getStatus, query.getStatus())
                        .eq(query.getLastStatus() != null && !query.getLastStatus().isBlank(),
                                AppJob::getLastStatus, query.getLastStatus())
                        .orderByDesc(AppJob::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
                query.getPageNum(), query.getPageSize());
    }

    public AppJob detail(Long id) {
        AppJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new ServiceException(ErrorCode.JOB_NOT_FOUND);
        }
        return job;
    }

    /** 列表页顶部统计 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", jobMapper.selectCount(null));
        stats.put("enabled", jobMapper.selectCount(new LambdaQueryWrapper<AppJob>()
                .eq(AppJob::getStatus, 1)));
        stats.put("failing", jobMapper.selectCount(new LambdaQueryWrapper<AppJob>()
                .gt(AppJob::getFailStreak, 0)));
        stats.put("todayRuns", logMapper.selectCount(new LambdaQueryWrapper<AppJobLog>()
                .ge(AppJobLog::getTriggerTime, LocalDate.now().atStartOfDay())));
        stats.put("running", dispatcher.runningLogIds().size());
        stats.put("schedulerEnabled", scheduler.enabled());
        stats.put("hostChannelAvailable", jobChannel.available());
        return stats;
    }

    // ==================== 任务写操作 ====================

    public AppJob create(JobBody body) {
        validate(body, null);
        AppJob job = new AppJob();
        apply(job, body);
        job.setFailStreak(0);
        jobMapper.insert(job);
        if (job.getStatus() != null && job.getStatus() == 1) {
            scheduler.refresh(job.getId());
        }
        log.info("定时任务「{}」已创建（handler={}，status={}）",
                job.getJobName(), job.getHandler(), job.getStatus());
        return jobMapper.selectById(job.getId());
    }

    public AppJob update(Long id, JobBody body) {
        detail(id);
        validate(body, id);
        AppJob patch = new AppJob();
        patch.setId(id);
        apply(patch, body);
        jobMapper.updateById(patch);
        // MyBatis-Plus 的 NOT_NULL 策略会静默忽略 null：要真正清空确认关键字必须显式 set
        if (patch.getConfirmKeyword() == null) {
            jobMapper.update(null, new LambdaUpdateWrapper<AppJob>()
                    .eq(AppJob::getId, id)
                    .set(AppJob::getConfirmKeyword, null));
        }
        scheduler.refresh(id);
        return jobMapper.selectById(id);
    }

    public void delete(Long id, String confirm) {
        AppJob job = detail(id);
        requireConfirm(confirm, "DELETE JOB " + job.getJobName());
        scheduler.unregister(id);
        jobMapper.deleteById(id);
        log.info("定时任务「{}」(id={}) 已删除；其历史日志保留（job_name 已快照）",
                job.getJobName(), id);
    }

    public AppJob copy(Long id) {
        AppJob source = detail(id);
        AppJob copy = new AppJob();
        copy.setJobName(uniqueCopyName(source.getJobName()));
        copy.setJobDesc(source.getJobDesc());
        copy.setExecutorId(source.getExecutorId());
        copy.setHandler(source.getHandler());
        copy.setHandlerParam(source.getHandlerParam());
        copy.setCronExpr(source.getCronExpr());
        copy.setRouteStrategy(source.getRouteStrategy());
        copy.setBlockStrategy(source.getBlockStrategy());
        copy.setTimeoutSec(source.getTimeoutSec());
        copy.setRetryCount(source.getRetryCount());
        copy.setConfirmKeyword(source.getConfirmKeyword());
        // 复制出来默认停用：避免「复制后立刻双跑」这种低级事故
        copy.setStatus(0);
        copy.setFailStreak(0);
        jobMapper.insert(copy);
        return jobMapper.selectById(copy.getId());
    }

    public AppJob toggle(Long id, boolean enable) {
        AppJob job = detail(id);
        if (enable) {
            // 启用前必须重新过一遍校验：禁用期间命令可能已移出白名单、执行器可能已删
            JobBody probe = toBody(job);
            probe.setStatus(1);
            validate(probe, id);
        }
        AppJob patch = new AppJob();
        patch.setId(id);
        patch.setStatus(enable ? 1 : 0);
        jobMapper.updateById(patch);
        scheduler.refresh(id);
        return jobMapper.selectById(id);
    }

    /**
     * 立即执行一次（不影响原有 cron 计划）。
     *
     * @param override 临时参数覆盖
     * @return 本次调度日志 ID（前端据此跳转/轮询日志）
     */
    public Long run(Long id, Map<String, Object> override) {
        detail(id);
        Long logId = dispatcher.trigger(id, JobEnums.TRIGGER_MANUAL, override);
        if (logId == null) {
            throw new ServiceException(ErrorCode.JOB_NOT_FOUND);
        }
        // 被阻塞策略丢弃、或被前置检查（停用 / 执行器熔断 / 宿主通道不可用）拦下时，
        // trigger 同样会返回一个 logId。从前这里直接 return，接口回「已派发执行，请到
        // 日志查看结果」，可实际上什么都没跑 —— 用户得自己进日志页才发现。
        // 现在把「没跑」如实报成失败，原因就用 trigger 当场写下的那句话。
        AppJobLog entry = logMapper.selectById(logId);
        if (entry != null && JobEnums.STATUS_DISCARDED.equals(entry.getStatus())) {
            String reason = entry.getTriggerMsg() == null ? "被阻塞策略丢弃" : entry.getTriggerMsg();
            throw new ServiceException(ErrorCode.JOB_TRIGGER_DISCARDED, "任务未被执行：" + reason);
        }
        return logId;
    }

    /** 停止正在执行的实例（需确认关键字） */
    public Map<String, Object> stop(Long id, String confirm) {
        AppJob job = detail(id);
        requireConfirm(confirm, "STOP JOB " + job.getJobName());
        JobDispatcher.StopResult result = dispatcher.stop(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result == null) {
            payload.put("stopped", false);
            payload.put("message", "该任务当前没有正在执行的实例");
            return payload;
        }
        payload.put("stopped", true);
        payload.put("logId", result.logId() == null ? null : String.valueOf(result.logId()));
        payload.put("terminated", result.terminated());
        payload.put("message", result.terminated()
                ? "已终止面板侧执行线程"
                : "已标记为 KILLED；真实执行体在宿主侧，进程可能仍在运行");
        return payload;
    }

    // ==================== 元数据（供前端渲染） ====================

    /** 4 类处理器的表单 schema */
    public List<JobHandler.HandlerSchema> handlers() {
        List<JobHandler.HandlerSchema> schemas = new ArrayList<>();
        for (String type : List.of(JobEnums.HANDLER_SHELL, JobEnums.HANDLER_HTTP,
                JobEnums.HANDLER_SERVICE, JobEnums.HANDLER_INTERNAL)) {
            JobHandler handler = dispatcher.handlers().get(type);
            if (handler != null) {
                schemas.add(handler.schema());
            }
        }
        return schemas;
    }

    /** 可用命令白名单（含宿主侧是否真实存在） */
    public List<Map<String, Object>> commands() {
        Map<String, String> tools = jobChannel.available()
                && jobChannel.capability().getTools() != null
                ? jobChannel.capability().getTools() : Map.of();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String name : commandExecutor.getWhitelist().stream().sorted().toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("available", tools.containsKey(name));
            item.put("path", tools.get(name));
            items.add(item);
        }
        return items;
    }

    /** 枚举字典（策略 / 状态 / 内置任务 / 受保护单元 / 各类上限） */
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("handlers", List.copyOf(JobEnums.HANDLERS));
        options.put("routeStrategies", List.copyOf(JobEnums.ROUTE_STRATEGIES));
        options.put("blockStrategies", List.copyOf(JobEnums.BLOCK_STRATEGIES));
        options.put("statuses", List.copyOf(JobEnums.STATUSES));
        options.put("executorTypes", List.copyOf(JobEnums.EXECUTOR_TYPES));
        options.put("executorStatuses", List.copyOf(JobEnums.EXECUTOR_STATUSES));
        options.put("destructiveServiceActions", List.copyOf(JobEnums.DESTRUCTIVE_SERVICE_ACTIONS));
        options.put("protectedUnits", List.copyOf(ProtectedUnits.all()));
        options.put("internalTasks", internalTaskRegistry.all().stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", task.code());
            item.put("label", task.label());
            item.put("description", task.description());
            item.put("fields", task.fields());
            item.put("freeFormParams", task.freeFormParams());
            return item;
        }).toList());
        options.put("minIntervalSeconds", properties.getLimits().getMinIntervalSeconds());
        options.put("maxTimeoutSeconds", properties.getLimits().getMaxTimeoutSeconds());
        options.put("maxRetryCount", properties.getLimits().getMaxRetryCount());
        options.put("logRetentionDays", properties.getLog().getRetentionDays());
        return options;
    }

    /**
     * cron 校验 + 未来 5 次执行时间预览。
     *
     * <p>间隔过短时也返回 {@code nextTimes}（用户能看到「到底有多密」），只是标记为不可用。
     */
    public CronPreviewVO validateCron(String expr) {
        CronPreviewVO vo = new CronPreviewVO();
        vo.setMinIntervalSeconds(properties.getLimits().getMinIntervalSeconds());
        try {
            List<LocalDateTime> times = JobCron.nextTimes(expr, 5);
            vo.setNextTimes(times.stream().map(JobService::format).toList());
            if (times.size() > 1) {
                long minGap = Long.MAX_VALUE;
                for (int i = 1; i < times.size(); i++) {
                    minGap = Math.min(minGap,
                            Duration.between(times.get(i - 1), times.get(i)).getSeconds());
                }
                vo.setIntervalSeconds(minGap);
            }
            JobCron.validateMinInterval(expr, properties.getLimits().getMinIntervalSeconds());
            vo.setValid(true);
        } catch (ServiceException e) {
            vo.setValid(false);
            vo.setMessage(e.getMessage());
        }
        return vo;
    }

    public List<String> nextTimes(Long id, int n) {
        AppJob job = detail(id);
        return JobCron.nextTimes(job.getCronExpr(), n).stream().map(JobService::format).toList();
    }

    // ==================== 执行器 ====================

    public List<ExecutorVO> executors() {
        return executorMapper.selectList(new LambdaQueryWrapper<AppExecutor>()
                        .orderByAsc(AppExecutor::getId))
                .stream().map(JobService::toVo).toList();
    }

    public ExecutorVO createExecutor(ExecutorBody body) {
        if (!JobEnums.TYPE_HTTP.equals(body.getType())) {
            throw new ServiceException(ErrorCode.JOB_EXECUTOR_BUILTIN_PROTECTED,
                    "内置执行器由系统内置，此处只能登记 HTTP 类型的外部执行器");
        }
        Long duplicate = executorMapper.selectCount(new LambdaQueryWrapper<AppExecutor>()
                .eq(AppExecutor::getAppName, body.getAppName().trim()));
        if (duplicate != null && duplicate > 0) {
            throw new ServiceException(ErrorCode.JOB_NAME_EXISTS,
                    "执行器 AppName 已存在：" + body.getAppName());
        }
        AppExecutor executor = new AppExecutor();
        executor.setAppName(body.getAppName().trim());
        executor.setExecutorName(body.getExecutorName());
        executor.setType(JobEnums.TYPE_HTTP);
        executor.setBaseUrl(normalizeBaseUrl(body.getBaseUrl()));
        executor.setAuthToken(body.getAuthToken());
        executor.setStatus(JobEnums.EXEC_DISABLED.equals(body.getStatus())
                ? JobEnums.EXEC_DISABLED : JobEnums.EXEC_AVAILABLE);
        executor.setFailStreak(0);
        executor.setRemark(body.getRemark());
        executorMapper.insert(executor);
        return toVo(executorMapper.selectById(executor.getId()));
    }

    public ExecutorVO updateExecutor(Long id, ExecutorBody body) {
        AppExecutor existing = requireExecutor(id);
        AppExecutor patch = new AppExecutor();
        patch.setId(id);
        patch.setExecutorName(body.getExecutorName());
        patch.setRemark(body.getRemark());
        if (JobEnums.TYPE_BUILTIN.equals(existing.getType())) {
            // 内置执行器只允许改显示名与备注：地址与状态由系统固定
            log.info("编辑内置执行器：仅更新显示名与备注");
        } else {
            if (body.getBaseUrl() != null && !body.getBaseUrl().isBlank()) {
                patch.setBaseUrl(normalizeBaseUrl(body.getBaseUrl()));
            }
            if (body.getAuthToken() != null && !body.getAuthToken().isBlank()) {
                patch.setAuthToken(body.getAuthToken());
            }
            if (body.getStatus() != null && !body.getStatus().isBlank()) {
                if (!JobEnums.EXECUTOR_STATUSES.contains(body.getStatus())) {
                    throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                            "执行器状态不合法：" + body.getStatus());
                }
                patch.setStatus(body.getStatus());
            }
        }
        executorMapper.updateById(patch);
        return toVo(executorMapper.selectById(id));
    }

    public void deleteExecutor(Long id, String confirm) {
        AppExecutor executor = requireExecutor(id);
        if (JobEnums.TYPE_BUILTIN.equals(executor.getType())) {
            throw new ServiceException(ErrorCode.JOB_EXECUTOR_BUILTIN_PROTECTED,
                    "内置执行器不允许删除");
        }
        requireConfirm(confirm, "DELETE EXECUTOR " + executor.getAppName());
        Long references = jobMapper.selectCount(new LambdaQueryWrapper<AppJob>()
                .eq(AppJob::getExecutorId, id));
        if (references != null && references > 0) {
            throw new ServiceException(ErrorCode.JOB_RUNNING,
                    "该执行器仍被 " + references + " 个任务引用，请先调整或删除这些任务");
        }
        executorMapper.deleteById(id);
    }

    /** 连通性探测：内置执行器直接置为可用；外部执行器 POST {base_url}/beat（5s 超时） */
    public ExecutorVO testExecutor(Long id) {
        AppExecutor executor = requireExecutor(id);
        AppExecutor patch = new AppExecutor();
        patch.setId(id);
        if (JobEnums.TYPE_BUILTIN.equals(executor.getType())) {
            patch.setStatus(JobEnums.EXEC_AVAILABLE);
            patch.setFailStreak(0);
            patch.setLastBeatAt(LocalDateTime.now());
            executorMapper.updateById(patch);
            clearExecutorError(id);
            return toVo(executorMapper.selectById(id));
        }
        String base = executor.getBaseUrl();
        if (base == null || base.isBlank()) {
            throw new ServiceException(ErrorCode.JOB_EXECUTOR_UNAVAILABLE, "未配置 base_url");
        }
        String url = base.endsWith("/") ? base + "beat" : base + "/beat";
        Map<String, String> headers = new LinkedHashMap<>();
        if (executor.getAuthToken() != null && !executor.getAuthToken().isBlank()) {
            headers.put("Authorization", "Bearer " + executor.getAuthToken());
        }
        boolean ok;
        String error = null;
        try {
            JobHttpClient.Response response = httpClient.send("POST", url, headers,
                    "application/json", "{}", 5);
            ok = response.status() >= 200 && response.status() < 300;
            if (!ok) {
                error = "HTTP " + response.status() + "："
                        + (response.body() == null ? "" : response.body());
            }
        } catch (RuntimeException e) {
            ok = false;
            error = e.getMessage();
        }
        int streak = ok ? 0 : (executor.getFailStreak() == null ? 0 : executor.getFailStreak()) + 1;
        patch.setFailStreak(streak);
        patch.setStatus(ok ? JobEnums.EXEC_AVAILABLE : JobEnums.EXEC_UNREACHABLE);
        if (ok) {
            patch.setLastBeatAt(LocalDateTime.now());
        } else {
            patch.setLastError(JobSupport.truncate(error, 480));
        }
        executorMapper.updateById(patch);
        if (ok) {
            clearExecutorError(id);
        }
        return toVo(executorMapper.selectById(id));
    }

    // ==================== 内部方法 ====================

    private void validate(JobBody body, Long selfId) {
        String jobName = body.getJobName() == null ? null : body.getJobName().trim();
        if (jobName == null || jobName.isEmpty()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "任务名不能为空");
        }
        if (!JobEnums.HANDLERS.contains(body.getHandler())) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "未知的处理器：" + body.getHandler());
        }
        Long duplicate = jobMapper.selectCount(new LambdaQueryWrapper<AppJob>()
                .eq(AppJob::getJobName, jobName)
                .ne(selfId != null, AppJob::getId, selfId));
        if (duplicate != null && duplicate > 0) {
            throw new ServiceException(ErrorCode.JOB_NAME_EXISTS, "任务名已存在：" + jobName);
        }
        JobCron.validateMinInterval(body.getCronExpr(), properties.getLimits().getMinIntervalSeconds());

        AppExecutor executor = requireExecutor(body.getExecutorId());
        if (JobEnums.EXEC_DISABLED.equals(executor.getStatus())) {
            throw new ServiceException(ErrorCode.JOB_EXECUTOR_UNAVAILABLE,
                    "执行器已停用：" + executor.getAppName());
        }

        int timeout = body.getTimeoutSec() == null ? 300 : body.getTimeoutSec();
        if (timeout < 1 || timeout > properties.getLimits().getMaxTimeoutSeconds()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "超时需在 1~" + properties.getLimits().getMaxTimeoutSeconds()
                            + " 秒之间，实际：" + timeout);
        }
        int retry = body.getRetryCount() == null ? 0 : body.getRetryCount();
        if (retry < 0 || retry > properties.getLimits().getMaxRetryCount()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "重试次数需在 0~" + properties.getLimits().getMaxRetryCount()
                            + " 之间，实际：" + retry);
        }
        if (body.getRouteStrategy() != null
                && !JobEnums.ROUTE_STRATEGIES.contains(body.getRouteStrategy())) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "不支持的路由策略：" + body.getRouteStrategy());
        }
        if (body.getBlockStrategy() != null
                && !JobEnums.BLOCK_STRATEGIES.contains(body.getBlockStrategy())) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "不支持的阻塞策略：" + body.getBlockStrategy());
        }
        if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "状态只能是 0（停用）或 1（启用）");
        }

        JobHandler handler = dispatcher.handlers().get(body.getHandler());
        if (handler == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "处理器未装载：" + body.getHandler());
        }
        AppJob probe = new AppJob();
        probe.setJobName(jobName);
        probe.setHandler(body.getHandler());
        probe.setConfirmKeyword(body.getConfirmKeyword() == null
                ? null : body.getConfirmKeyword().trim());
        handler.validate(body.getHandlerParam() == null ? Map.of() : body.getHandlerParam(), probe);
    }

    private void apply(AppJob job, JobBody body) {
        job.setJobName(body.getJobName().trim());
        job.setJobDesc(body.getJobDesc());
        job.setExecutorId(body.getExecutorId());
        job.setHandler(body.getHandler());
        job.setHandlerParam(JobSupport.toJson(
                body.getHandlerParam() == null ? Map.of() : body.getHandlerParam()));
        job.setCronExpr(body.getCronExpr().trim());
        job.setRouteStrategy(body.getRouteStrategy() == null
                ? JobEnums.ROUTE_FIRST : body.getRouteStrategy());
        job.setBlockStrategy(body.getBlockStrategy() == null
                ? JobEnums.BLOCK_SERIAL : body.getBlockStrategy());
        job.setTimeoutSec(body.getTimeoutSec() == null ? 300 : body.getTimeoutSec());
        job.setRetryCount(body.getRetryCount() == null ? 0 : body.getRetryCount());
        job.setStatus(body.getStatus() == null ? 0 : body.getStatus());
        job.setConfirmKeyword(body.getConfirmKeyword() == null || body.getConfirmKeyword().isBlank()
                ? null : body.getConfirmKeyword().trim());
    }

    private JobBody toBody(AppJob job) {
        JobBody body = new JobBody();
        body.setJobName(job.getJobName());
        body.setJobDesc(job.getJobDesc());
        body.setExecutorId(job.getExecutorId());
        body.setHandler(job.getHandler());
        body.setHandlerParam(JobSupport.toMap(job.getHandlerParam()));
        body.setCronExpr(job.getCronExpr());
        body.setRouteStrategy(job.getRouteStrategy());
        body.setBlockStrategy(job.getBlockStrategy());
        body.setTimeoutSec(job.getTimeoutSec());
        body.setRetryCount(job.getRetryCount());
        body.setStatus(job.getStatus());
        body.setConfirmKeyword(job.getConfirmKeyword());
        return body;
    }

    private String uniqueCopyName(String base) {
        for (int i = 1; i <= 50; i++) {
            String suffix = i == 1 ? "_copy" : "_copy" + i;
            String candidate = base + suffix;
            if (candidate.length() > 64) {
                candidate = base.substring(0, Math.max(0, 64 - suffix.length())) + suffix;
            }
            Long count = jobMapper.selectCount(new LambdaQueryWrapper<AppJob>()
                    .eq(AppJob::getJobName, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new ServiceException(ErrorCode.JOB_NAME_EXISTS, "复制失败：可用的副本名已用尽");
    }

    private AppExecutor requireExecutor(Long id) {
        if (id == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少执行器 id");
        }
        AppExecutor executor = executorMapper.selectById(id);
        if (executor == null) {
            throw new ServiceException(ErrorCode.JOB_EXECUTOR_UNAVAILABLE, "执行器不存在：" + id);
        }
        return executor;
    }

    private String normalizeBaseUrl(String url) {
        String trimmed = url == null ? null : url.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "执行器地址不能为空");
        }
        // 复用 SSRF 护栏：scheme 白名单 + 云元数据地址黑名单
        httpClient.validateUrl(trimmed);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 显式清空 last_error（updateById 的 NOT_NULL 策略无法把字段置 null） */
    private void clearExecutorError(Long id) {
        executorMapper.update(null, new LambdaUpdateWrapper<AppExecutor>()
                .eq(AppExecutor::getId, id)
                .set(AppExecutor::getLastError, null));
    }

    private void requireConfirm(String confirm, String expected) {
        if (confirm == null || !expected.equals(confirm.trim())) {
            throw new ServiceException(ErrorCode.JOB_CONFIRM_REQUIRED,
                    "该操作不可逆，请在确认框中输入：" + expected);
        }
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private static ExecutorVO toVo(AppExecutor executor) {
        ExecutorVO vo = new ExecutorVO();
        vo.setId(executor.getId() == null ? null : String.valueOf(executor.getId()));
        vo.setAppName(executor.getAppName());
        vo.setExecutorName(executor.getExecutorName());
        vo.setType(executor.getType());
        vo.setBaseUrl(executor.getBaseUrl());
        vo.setTokenSet(executor.getAuthToken() != null && !executor.getAuthToken().isBlank());
        vo.setAuthTokenMasked(JobSupport.maskToken(executor.getAuthToken()));
        vo.setStatus(executor.getStatus());
        vo.setFailStreak(executor.getFailStreak());
        vo.setLastBeatAt(executor.getLastBeatAt());
        vo.setLastError(executor.getLastError());
        vo.setRemark(executor.getRemark());
        vo.setCreatedAt(executor.getCreatedAt());
        return vo;
    }
}
