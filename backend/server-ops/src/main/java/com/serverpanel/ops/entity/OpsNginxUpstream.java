package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 上游组（多后端 + 负载策略）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_upstream")
public class OpsNginxUpstream {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long instanceId;

    private String name;

    /** round_robin / least_conn / ip_hash */
    private String strategy;

    /** [{"host":"127.0.0.1","port":8080,"weight":1,"max_fails":3,"backup":false}] */
    private String serversJson;

    private Integer keepalive;

    private String remark;

    private LocalDateTime createdAt;
}
