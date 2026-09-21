package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 防火墙规则（解析修正后的模型）。
 *
 * <p>旧模型的 {@code action} 只有 allow/deny 两值、且把多词动作（{@code REJECT IN}）
 * 的第二个词吃进了 {@code source} —— 本机 55 条规则里的 Fail2Ban 规则全是
 * {@code REJECT IN}，于是列表里出现「来源 = IN 172.238.101.222」这种污染数据。
 * 这里把 action / direction 拆开，并补上目标类型、来源类型、IPv6、来源标记。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallRule {

    /** 规则编号（ufw status numbered 的序号） */
    private Integer no;

    /** 目标，已剥离 (v6) 后缀，如 22/tcp、40000:40100/tcp、Anywhere */
    private String to;

    /** 目标类型：any / port / range / multi / app */
    private String toKind;

    /** allow / deny / reject / limit */
    private String action;

    /** 方向：in / out / fwd */
    private String direction;

    /** 来源，any 或具体 IP/网段 */
    private String from;

    /** 来源类型：any / ip / cidr */
    private String sourceKind;

    /** 是否为 IPv6 规则（ufw 用 (v6) 标记） */
    private boolean ipv6;

    /** 规则备注（ufw comment） */
    private String comment;

    /**
     * 来源标记：panel（面板写入）/ fail2ban / manual（人工带注释）/ unknown（无注释）。
     *
     * <p>用途：Fail2Ban 动态写入的规则由外部程序托管，面板删了它也会立刻被写回，
     * 因此必须识别出来并提示用户不要从面板侧删除。
     */
    private String provenance;

    /** 是否允许面板直接删除（Fail2Ban 规则与「拒绝所有」类规则置 false） */
    private boolean deletable;

    /**
     * 指纹：{@code to|action|from}。
     *
     * <p>删除按编号执行，而<u>编号会在每次增删后重排</u>；若前端拿的是旧编号，
     * 就可能删掉另一条规则。故删除前用指纹二次校验「编号指向的还是不是那条规则」。
     */
    private String fingerprint;
}
