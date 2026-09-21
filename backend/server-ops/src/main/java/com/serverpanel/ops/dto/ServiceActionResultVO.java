package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 服务操作结果（含执行后回读的真实状态）。
 *
 * <p>行为约定：**不只信退出码**。systemctl 有大量「退出 0 但状态未变」的场景
 * （例如 start 一个 ConditionXxx 不满足的单元），因此每个动作执行后都回读
 * is-active / is-enabled，把真实状态一并返回给前端。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceActionResultVO {

    private String name;

    private String action;

    private boolean ok;

    private int exitCode;

    private String stdout;

    private String stderr;

    /** 执行后回读的 active 状态 */
    private String activeAfter;

    /** 执行后回读的自启状态 */
    private String enabledAfter;

    /** 执行后是否处于失败态 */
    private Boolean failedAfter;

    /** 面向使用者的结果描述 */
    private String message;

    /** 是否需要 L3 二次确认（前端据此提示） */
    private boolean confirmRequired;

    /** 二次确认需键入的字符串 */
    private String confirmKeyword;
}
