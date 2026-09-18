package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划任务执行日志（只增不改）。
 */
@Data
@TableName("ops_cron_log")
public class OpsCronLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long jobId;

    /** 冗余任务名，防任务删除后丢失信息 */
    private String jobName;

    /** 成功为 0，异常为 -1，超时为 -2 */
    private Integer exitCode;

    /** 输出（截断 64KB） */
    private String output;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
