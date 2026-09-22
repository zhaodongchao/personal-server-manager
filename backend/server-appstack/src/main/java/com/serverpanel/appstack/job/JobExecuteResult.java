package com.serverpanel.appstack.job;

import java.util.Map;

/**
 * 一次执行的结果。
 *
 * @param success 是否成功
 * @param message 结果摘要（入 handle_msg）
 * @param output  执行输出（入 executor_output，由派发侧按上限截断）
 * @param metrics 结构化指标（预留，如影响行数、备份文件大小）
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public record JobExecuteResult(boolean success, String message, String output,
                               Map<String, Object> metrics) {

    public static JobExecuteResult ok(String message, String output) {
        return new JobExecuteResult(true, message, output, Map.of());
    }

    public static JobExecuteResult ok(String message) {
        return new JobExecuteResult(true, message, null, Map.of());
    }

    public static JobExecuteResult fail(String message, String output) {
        return new JobExecuteResult(false, message, output, Map.of());
    }

    public static JobExecuteResult fail(String message) {
        return new JobExecuteResult(false, message, null, Map.of());
    }
}
