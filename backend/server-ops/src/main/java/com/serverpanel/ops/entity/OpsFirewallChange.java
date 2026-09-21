package com.serverpanel.ops.entity;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 防火墙变更快照与回滚记录。
 *
 * <p>每次写操作都记录「变更前的完整规则清单」与「变更后的完整规则清单」，
 * 以及两者之间的结构差异与回滚所需的 op 列表。这样任何一次误操作都能
 * 被精确定位（哪条规则变了）并被撤销（按差集重放）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_firewall_change")
public class OpsFirewallChange {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** ufw / firewalld */
    private String backend;

    /** ADD_RULE / DELETE_RULE / ENABLE / DISABLE / SET_DEFAULT / RELOAD */
    private String op;

    private String ruleDesc;

    /** 变更前 ufw status numbered 原文 */
    private String beforeSnapshot;

    /** 变更后 ufw status numbered 原文 */
    private String afterSnapshot;

    /** 结构化差异 JSON */
    private String diffJson;

    /** 回滚脚本（代理 op 列表） */
    private String undoJson;

    /** 是否经过 L3 二次确认 */
    private Integer guardAck;

    /** 看门狗窗口秒数，null 表示未启用 */
    private Integer watchdogSeconds;

    private Integer rollbackable;

    private Integer rolledBack;

    /** 0 成功 1 失败 */
    private Integer result;

    private String errorMsg;

    private String operator;

    private String operatorIp;

    private LocalDateTime createdAt;
}
