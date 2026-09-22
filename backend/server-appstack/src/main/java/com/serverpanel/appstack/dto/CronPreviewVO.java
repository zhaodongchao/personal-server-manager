package com.serverpanel.appstack.dto;

import java.util.List;

import lombok.Data;

/**
 * cron 校验与预览出参。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class CronPreviewVO {

    /** 是否可用（含最小间隔校验） */
    private boolean valid;

    /** 不可用原因 */
    private String message;

    /** 未来 5 次执行时间（yyyy-MM-dd HH:mm:ss） */
    private List<String> nextTimes;

    /** 观测到的最小相邻间隔（秒） */
    private Long intervalSeconds;

    /** 允许的最小间隔（供前端提示） */
    private Integer minIntervalSeconds;
}
