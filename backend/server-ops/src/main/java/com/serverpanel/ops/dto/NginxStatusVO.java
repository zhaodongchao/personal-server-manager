package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * Nginx 状态 VO（运行态 + 实例信息 + 能力）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxStatusVO {

    /** 宿主通道是否可用 */
    private boolean channelOk;

    private String channelMessage;

    /** 实例是否存在 */
    private boolean instanceExists;

    private Long instanceId;

    private String instanceName;

    /** nginx 是否可执行 */
    private boolean nginxAvailable;

    private String nginxVersion;

    private String nginxBinary;

    private String confPath;

    /** nginx -t 是否通过 */
    private boolean configValid;

    private String configMessage;

    private String certbotVersion;

    /** 站点数 / 上游组数 / 四层转发数 / 证书数 */
    private long siteCount;

    private long upstreamCount;

    private long streamCount;

    private long certCount;

    /** 即将到期（<=30 天）证书域名 */
    private List<String> expiringCerts;
}
