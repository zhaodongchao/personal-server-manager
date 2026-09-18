package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * 防火墙整体状态。
 */
@Data
public class FirewallStatus {

    /** ufw / firewalld / none */
    private String backend;

    private boolean active;

    private List<FirewallRule> rules;
}
