package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * Nginx 四层转发请求体。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxStreamBody {

    private Long id;

    private Long instanceId;

    private String name;

    /** tcp / udp */
    private String protocol;

    private Integer listenPort;

    private String upstreamHost;

    private Integer upstreamPort;

    private Integer proxyTimeout;

    private String remark;
}
