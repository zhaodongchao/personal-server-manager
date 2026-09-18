package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作审计日志 sys_audit_log（只增不改，无 updated_at）。
 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 操作人登录名 */
    private String operator;

    /** 业务模块 system/file/ops/appstack */
    private String module;

    /** 动作，如 user:add */
    private String action;

    /** 类#方法 */
    private String method;

    private String requestUri;

    private String requestMethod;

    /** 入参 JSON（截断 2KB） */
    private String params;

    /** 响应业务码 */
    private Integer resultCode;

    private String errorMsg;

    /** 耗时毫秒 */
    private Long durationMs;

    private String ip;

    private String userAgent;
}
