package com.serverpanel.ops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划任务总览统计（页面顶部统计卡数据来源）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class CronSummaryVO {

    private long total;

    private long enabled;

    private long disabled;

    /** 正在执行中的任务数 */
    private long running;

    /** 近 24 小时失败次数（含超时） */
    private long failed24h;

    /** 近 24 小时成功次数 */
    private long success24h;

    /** 最近一次执行时间（不限任务） */
    private LocalDateTime lastRunAt;

    /** 最近一次执行结果：success / fail / timeout */
    private String lastResult;

    /** 距离最近一次即将执行的时间（启用任务中最小的 next_run_at） */
    private LocalDateTime nextRunAt;
}
