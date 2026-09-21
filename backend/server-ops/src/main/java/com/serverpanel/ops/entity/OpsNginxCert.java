package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 证书（Let's Encrypt / 手动）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_cert")
public class OpsNginxCert {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long instanceId;

    private String domain;

    /** letsencrypt / custom */
    private String type;

    private String certPath;

    private String keyPath;

    private String issuer;

    private LocalDateTime notBefore;

    private LocalDateTime notAfter;

    private Integer autoRenew;

    private LocalDateTime lastRenewAt;

    /** valid / expiring / expired / pending */
    private String status;

    private String remark;

    private LocalDateTime createdAt;
}
