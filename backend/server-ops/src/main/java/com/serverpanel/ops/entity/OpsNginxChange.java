package com.serverpanel.ops.entity;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 变更快照与回滚记录。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_change")
public class OpsNginxChange {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)

    private Long instanceId;

    /** CREATE_SITE / UPDATE_SITE / DELETE_SITE / TOGGLE / ISSUE_CERT / RENEW_CERT / RELOAD ... */
    private String op;

    /** site / upstream / stream / cert */
    private String targetType;

    private Long targetId;

    /** 受影响的配置文件绝对路径（回滚时据此恢复） */
    private String confPath;

    private String beforeConf;

    private String afterConf;

    private String rollbackConf;

    private Integer rolledBack;

    /** 0 成功 1 失败 */
    private Integer result;

    private String errorMsg;

    private String operator;

    private String operatorIp;

    private LocalDateTime createdAt;
}
