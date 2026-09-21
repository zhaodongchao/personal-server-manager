package com.serverpanel.ops.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * cron 表达式预览结果。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class CronPreviewVO {

    /** 表达式是否合法 */
    private boolean valid;

    private String cronExpr;

    /** 人话描述，例如「每天 02:00」；无法翻译时回退为表达式原文 */
    private String humanExpr;

    /** 未来 N 次执行时间 */
    private List<LocalDateTime> nextTimes;

    /** 校验失败原因（valid=false 时有值） */
    private String message;
}
