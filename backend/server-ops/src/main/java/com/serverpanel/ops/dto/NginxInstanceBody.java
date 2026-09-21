package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * Nginx 实例请求体（手工指定 / 自动探测结果回写）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class NginxInstanceBody {

    private Long id;

    private String name;

    /** auto / manual */
    private String detectMode;

    private String binaryPath;

    private String prefix;

    private String confPath;

    private String managedDir;

    private String streamDir;

    private String certDir;

    private String acmeWebroot;

    private String logDir;

    private Integer status;

    private String remark;
}
