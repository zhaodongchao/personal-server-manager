package com.serverpanel.ops.dto;

import com.serverpanel.framework.command.HostCapability;
import lombok.Data;

import java.util.List;

/**
 * 防火墙整体状态。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallStatus {

    /** ufw / firewalld / none */
    private String backend;

    private boolean available;

    private boolean active;

    private String version;

    /** ufw 是否启用 IPv6 规则（IPV6=yes） */
    private boolean ipv6;

    private String logging;

    private DefaultPolicy defaultPolicy;

    private int ruleCount;

    private List<FirewallRule> rules;

    /** 生存线信息：SSH 端口、面板端口、来源 IP */
    private FirewallGuard guard;

    /** 宿主通道摘要：不可用时所有写操作都应禁用 */
    private HostCapability hostChannel;

    /** 降级原因（如防火墙未启用、通道不可用） */
    private String message;

    /** 默认策略 */
    @Data
    public static class DefaultPolicy {
        private String incoming;
        private String outgoing;
        private String routed;
    }
}
