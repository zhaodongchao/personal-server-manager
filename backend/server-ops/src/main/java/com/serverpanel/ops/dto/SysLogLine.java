package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 一条 systemd journal 日志。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class SysLogLine {

    /** ISO-8601 本地时间 */
    private String time;

    /** epoch 毫秒 */
    private Long timestamp;

    private Integer pid;

    /** 日志标识（SYSLOG_IDENTIFIER / _COMM） */
    private String identifier;

    /** 原始 priority 数值 0~7 */
    private Integer level;

    /** 级别名：emerg/alert/crit/err/warning/notice/info/debug */
    private String levelName;

    private String unit;

    private String message;
}
