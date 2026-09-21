package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 防火墙操作结果：命令原文 + 前后快照差异 + 看门狗状态。
 *
 * <p>把「实际执行了什么命令」回传给前端很重要：防火墙是高危操作，
 * 用户需要能核对、能拿去复盘，而不是只看到一个「操作成功」。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallActionResultVO {

    private boolean ok;

    private String message;

    /** 实际下发的命令（argv 拼接展示，仅用于展示与审计） */
    private String command;

    /** 本次变更记录 ID（可用于查看 diff / 回滚） */
    private Long changeId;

    /** 新增/删除的规则描述 */
    private java.util.List<String> diff;

    /** 看门狗（防锁死倒计时）；未启用为 null */
    private WatchdogBrief watchdog;

    @Data
    public static class WatchdogBrief {
        private String id;
        private int secondsLeft;
        private String expiresAt;
        private String reason;
    }
}
