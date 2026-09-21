package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 四层转发（stream tcp/udp）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_stream")
public class OpsNginxStream {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long instanceId;

    private String name;

    /** tcp / udp */
    private String protocol;

    private Integer listenPort;

    private String upstreamHost;

    private Integer upstreamPort;

    private Integer proxyTimeout;

    private String confPath;

    private String confHash;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createdAt;
}
