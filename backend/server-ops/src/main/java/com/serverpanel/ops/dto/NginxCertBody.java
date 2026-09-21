package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * Nginx 证书请求体（ACME 申请 / 手动上传元信息）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxCertBody {

    private Long id;

    private Long instanceId;

    private String domain;

    /** letsencrypt / custom */
    private String type;

    /** ACME 申请用邮箱 */
    private String email;

    /** ACME 模式 http01 / dns01 */
    private String mode;

    /** DNS-01 二段确认值 */
    private String dnsTxt;

    private Integer autoRenew;

    /** 手动证书：证书内容 */
    private String certContent;

    /** 手动证书：私钥内容 */
    private String keyContent;
}
