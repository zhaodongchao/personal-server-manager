package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 实例（可配置、默认自动探测）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_instance")
public class OpsNginxInstance {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** auto（探测生成）/ manual（手工指定） */
    private String detectMode;

    /** nginx 可执行路径（binary 为 MySQL 保留字，故列名用 binary_path；属性名 binaryPath 避免别名冲突） */
    @TableField("binary_path")
    private String binaryPath;

    /** --prefix */
    private String prefix;

    /** 主配置 nginx.conf 绝对路径 */
    private String confPath;

    /** 面板托管站点目录 */
    private String managedDir;

    /** stream 托管目录 */
    private String streamDir;

    /** 证书目录 */
    private String certDir;

    /** ACME HTTP-01 webroot */
    private String acmeWebroot;

    /** 日志目录 */
    private String logDir;

    /** 是否默认实例 */
    private Integer defaultFlag;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createdAt;
}
