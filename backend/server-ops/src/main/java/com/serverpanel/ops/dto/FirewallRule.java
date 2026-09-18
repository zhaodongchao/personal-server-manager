package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 防火墙规则。
 */
@Data
public class FirewallRule {

    /** ufw 为编号，firewalld 为端口串 */
    private String id;

    /** 形如 22/tcp */
    private String port;

    /** allow / deny */
    private String action;

    /** 来源 IP/网段；"-" 表示任意来源 */
    private String source;
}
