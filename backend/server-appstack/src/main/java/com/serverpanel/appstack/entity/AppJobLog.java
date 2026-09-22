package com.serverpanel.appstack.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 定时任务调度日志（app_job_log）—— 单表双段。
 *
 * <p>刻意借鉴 xxl-job 的 {@code trigger_*} / {@code handle_*} 双段模型：
 * <ul>
 *   <li>{@code trigger_code = 500} —— <b>到点了但没能派发出去</b>
 *       （执行器不可达、被阻塞策略丢弃、任务已删除）；</li>
 *   <li>{@code handle_code = 500} —— <b>派发出去了但执行失败</b>。</li>
 * </ul>
 * 这两类失败必须能分辨，否则「任务为什么没结果」永远说不清。
 *
 * <p>本表**只增不改**（无 updated_at），因此不继承 {@code BaseEntity}
 * （基类的 {@code INSERT_UPDATE} 填充会写不存在的列）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@TableName("app_job_log")
public class AppJobLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，应用侧雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 任务 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long jobId;

    /** 任务名快照（任务删除后日志仍可读） */
    private String jobName;

    /** 执行器 AppName 快照 */
    private String executorAppName;

    /** 实际派发地址（local 或外部 URL） */
    private String executorAddress;

    /** 处理器快照 */
    private String handler;

    /** CRON 自动 / MANUAL 手动 */
    private String triggerType;

    /** 调度时间 */
    private LocalDateTime triggerTime;

    /** 0 已派发 / 500 派发失败 / 404 任务已不存在 */
    private Integer triggerCode;

    /** 调度说明（阻塞丢弃 / 执行器不可达等） */
    private String triggerMsg;

    /** 开始执行时间 */
    private LocalDateTime handleTime;

    /** 0 成功 / 500 失败 */
    private Integer handleCode;

    /** 执行结果摘要 */
    private String handleMsg;

    /** 执行耗时（毫秒） */
    private Long handleDurationMs;

    /** RUNNING/SUCCESS/FAILED/TIMEOUT/DISCARDED/KILLED */
    private String status;

    /** 第几次尝试（0 为首次） */
    private Integer retryIndex;

    /** 执行输出全文（截断 64KB） */
    private String executorOutput;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
