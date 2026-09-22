package com.serverpanel.appstack.job;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.entity.AppExecutor;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.entity.AppJobLog;
import com.serverpanel.appstack.mapper.AppJobLogMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 调度日志记账器 —— 调度段（trigger）与执行段（handle）的落库入口。
 *
 * <p>把「写日志」集中在一处，是为了保证双段语义在所有路径上一致：
 * 无论失败发生在**派发之前**（执行器不可达、阻塞策略丢弃）还是**派发之后**（handler 抛错、
 * 超时），都必须能一眼分清。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLogRecorder {

    private final AppJobLogMapper logMapper;

    private final JobProperties properties;

    /** 落一条调度记录（初始状态由调用方给定），返回含主键的实体 */
    public AppJobLog recordTrigger(AppJob job, AppExecutor executor, String triggerType,
                                   int triggerCode, String triggerMsg, String status) {
        AppJobLog entry = new AppJobLog();
        entry.setJobId(job.getId());
        entry.setJobName(job.getJobName());
        entry.setExecutorAppName(executor == null ? null : executor.getAppName());
        entry.setExecutorAddress(executor == null ? null : executor.getBaseUrl());
        entry.setHandler(job.getHandler());
        entry.setTriggerType(triggerType);
        entry.setTriggerTime(LocalDateTime.now());
        entry.setTriggerCode(triggerCode);
        entry.setTriggerMsg(JobSupport.truncate(triggerMsg, 900));
        entry.setStatus(status);
        entry.setRetryIndex(0);
        if (JobEnums.STATUS_RUNNING.equals(status)) {
            entry.setHandleTime(LocalDateTime.now());
        }
        logMapper.insert(entry);
        return entry;
    }

    /**
     * 回填执行段并落最终状态。
     *
     * <p><b>单向门</b>：只有当前状态仍为 {@code RUNNING} 的日志才会被回填。执行线程可能在
     * 「已被 stop / COVER_EARLY 落定为 KILLED」之后才跑完并把结果写回来，若无条件覆盖，
     * 用户在日志页看到的会是「失败」，而他刚刚按下的是「停止」。
     *
     * @return 实际更新行数；0 表示该日志已是终态（已被终止标记），调用方不应再回写任务统计
     */
    public int recordHandle(Long logId, boolean success, String message, String output,
                            long durationMs, String status, int retryIndex) {
        if (logId == null) {
            return 0;
        }
        LambdaUpdateWrapper<AppJobLog> wrapper = new LambdaUpdateWrapper<AppJobLog>()
                .eq(AppJobLog::getId, logId)
                .eq(AppJobLog::getStatus, JobEnums.STATUS_RUNNING)
                .set(AppJobLog::getHandleTime, LocalDateTime.now())
                .set(AppJobLog::getHandleCode,
                        success ? JobEnums.HANDLE_SUCCESS : JobEnums.HANDLE_FAILED)
                .set(AppJobLog::getHandleMsg, JobSupport.truncate(message, 900))
                .set(AppJobLog::getHandleDurationMs, durationMs)
                .set(AppJobLog::getStatus, status)
                .set(AppJobLog::getRetryIndex, retryIndex)
                .set(AppJobLog::getExecutorOutput,
                        JobSupport.truncate(output, properties.getLog().getMaxOutputBytes()));
        return logMapper.update(null, wrapper);
    }

    /**
     * 标记为被 COVER_EARLY 覆盖 / 被手动停止。
     *
     * <p>同样以 {@code status = RUNNING} 为前置条件：若该次执行其实已经正常结束
     * （SUCCESS / FAILED / TIMEOUT），迟到的「终止」不该把既成事实改写掉。
     */
    public void markKilled(Long logId, String reason) {
        if (logId == null) {
            return;
        }
        LambdaUpdateWrapper<AppJobLog> wrapper = new LambdaUpdateWrapper<AppJobLog>()
                .eq(AppJobLog::getId, logId)
                .eq(AppJobLog::getStatus, JobEnums.STATUS_RUNNING)
                .set(AppJobLog::getStatus, JobEnums.STATUS_KILLED)
                .set(AppJobLog::getHandleCode, JobEnums.HANDLE_FAILED)
                .set(AppJobLog::getHandleMsg, JobSupport.truncate(reason, 900))
                .set(AppJobLog::getHandleTime, LocalDateTime.now());
        logMapper.update(null, wrapper);
    }

    /**
     * 清理日志。
     *
     * @param beforeTime 只删该时间之前（触发时间）
     * @param jobId      只删该任务
     * @param status     只删该状态
     * @return 删除条数
     */
    public int purge(LocalDateTime beforeTime, Long jobId, String status) {
        LambdaQueryWrapper<AppJobLog> wrapper = new LambdaQueryWrapper<AppJobLog>()
                .lt(beforeTime != null, AppJobLog::getTriggerTime, beforeTime)
                .eq(jobId != null, AppJobLog::getJobId, jobId)
                .eq(status != null && !status.isBlank(), AppJobLog::getStatus, status);
        return logMapper.delete(wrapper);
    }
}
