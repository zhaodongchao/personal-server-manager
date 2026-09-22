package com.serverpanel.appstack.job;

import java.util.Map;
import java.util.function.Consumer;

import com.serverpanel.appstack.entity.AppExecutor;
import com.serverpanel.appstack.entity.AppJob;

/**
 * 单次执行的上下文（handler 执行时可见的一切）。
 *
 * <p>参数里不含任何「执行器凭据」以外的敏感对象；外部执行器的令牌不进入上下文，
 * 由派发侧在出站请求时读取，避免它被写进执行输出。
 *
 * @param job      任务定义快照
 * @param executor 执行器
 * @param logId    本次调度日志 ID（重试轮次共用同一个）
 * @param timeoutSec 生效超时秒数
 * @param param    处理器参数（已解析为 Map）
 * @param logger   执行日志回调
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public record JobContext(AppJob job, AppExecutor executor, Long logId,
                         int timeoutSec, Map<String, Object> param,
                         Consumer<String> logger) {

    public Long jobId() {
        return job.getId();
    }

    public String jobName() {
        return job.getJobName();
    }

    public String handler() {
        return job.getHandler();
    }

    /** 是否派发到面板内置执行器（否则为外部 HTTP 执行器） */
    public boolean builtinExecutor() {
        return executor != null && JobEnums.TYPE_BUILTIN.equals(executor.getType());
    }
}
