package com.serverpanel.ops.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import lombok.Data;

/**
 * Nginx 操作结果 VO（渲染后的命令 + 变更 diff + 回滚信息）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxActionResultVO {

    /** 本次执行的关键命令描述（如 "nginx -t && nginx -s reload"） */
    private String command;

    /** 变更前后差异（简版） */
    private String diff;

    /** 是否已记录可回滚快照 */
    private boolean rollbackable;

    /** 变更记录 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long changeId;

    /** 提示信息 */
    private String message;

    /** DNS-01 两步流：需要用户在 DNS 添加的 TXT 记录名（如 _acme-challenge.example.com） */
    private String dnsTxtName;

    /** DNS-01 两步流：需要用户在 DNS 添加的 TXT 记录值 */
    private String dnsTxtValue;
}
