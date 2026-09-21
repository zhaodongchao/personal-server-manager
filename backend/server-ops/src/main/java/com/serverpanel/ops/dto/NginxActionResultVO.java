package com.serverpanel.ops.dto;

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
    private Long changeId;

    /** 提示信息 */
    private String message;
}
