package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nginx 站点（proxy / static）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@TableName("ops_nginx_site")
public class OpsNginxSite {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long instanceId;

    private String name;

    /** 域名列表，逗号分隔，首个为主域 */
    private String domains;

    /** proxy / static */
    private String siteType;

    private Long upstreamId;

    /** 内联上游，如 http://127.0.0.1:3000 */
    private String upstreamInline;

    private String staticRoot;

    /** off / letsencrypt / custom */
    private String sslMode;

    private Long certId;

    /** 80 -> 443 强制跳转 */
    private Integer httpRedirect;

    private Integer hsts;

    /** 自定义 location 列表 JSON */
    private String locationsJson;

    private String confPath;

    private String confHash;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createdAt;
}
