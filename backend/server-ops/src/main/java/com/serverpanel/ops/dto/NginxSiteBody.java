package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * Nginx 站点请求体（创建 / 更新）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxSiteBody {

    private Long id;

    private Long instanceId;

    private String name;

    /** 域名列表，首个为主域 */
    private List<String> domains;

    /** proxy / static */
    private String siteType;

    private Long upstreamId;

    private String upstreamInline;

    private String staticRoot;

    /** off / letsencrypt / custom */
    private String sslMode;

    private Long certId;

    private Boolean httpRedirect;

    private Boolean hsts;

    private List<NginxLocation> locations;

    private String remark;

    /**
     * 单个 location 块。
     */
    @Data
    public static class NginxLocation {

        private String path;

        /** proxy / static / redirect / deny */
        private String type;

        /** proxy 类型：上游地址 */
        private String upstream;

        /** static 类型：根目录 */
        private String staticRoot;

        /** redirect 类型：目标 URL */
        private String redirectTarget;
    }
}
