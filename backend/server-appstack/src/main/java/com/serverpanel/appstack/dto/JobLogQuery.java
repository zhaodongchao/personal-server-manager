package com.serverpanel.appstack.dto;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.serverpanel.common.core.PageQuery;

import lombok.Getter;
import lombok.Setter;

/**
 * 调度日志分页查询参数。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Getter
@Setter
public class JobLogQuery extends PageQuery {

    private Long jobId;

    /** 任务名快照模糊匹配 */
    private String jobName;

    private String status;

    private String handler;

    /** CRON / MANUAL */
    private String triggerType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
