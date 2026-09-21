package com.serverpanel.ops.entity;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划任务执行日志（只增不改）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_cron_log")
public class OpsCronLog {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)

    private Long jobId;

    /** 冗余任务名，防任务删除后丢失信息 */
    private String jobName;

    /**
     * 触发方式：
     * <ul>
     *   <li>{@code cron} —— 调度器定时触发</li>
     *   <li>{@code manual} —— 用户在页面点「立即执行」</li>
     *   <li>{@code retry} —— 补跑（misfire catch_up / run_once）</li>
     * </ul>
     */
    private String triggerType;

    /** 手动触发者（定时触发为 null） */
    private String operator;

    /** 成功为 0，异常为 -1，超时为 -2，并发跳过为 -3 */
    private Integer exitCode;

    /** 1 表示因超时被终止 */
    private Integer timedOut;

    /** 1 表示输出被截断（超过 64KB） */
    private Integer truncated;

    /** 输出（截断 64KB） */
    private String output;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
