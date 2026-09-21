package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * Nginx 上游组请求体。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxUpstreamBody {

    private Long id;

    private Long instanceId;

    private String name;

    /** round_robin / least_conn / ip_hash */
    private String strategy;

    private List<Server> servers;

    private Integer keepalive;

    private String remark;

    /**
     * 单个后端。
     */
    @Data
    public static class Server {

        private String host;

        private Integer port;

        private Integer weight;

        private Integer maxFails;

        private Boolean backup;
    }
}
