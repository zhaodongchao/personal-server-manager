package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 服务总览统计。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceSummaryVO {

    /** 全部已安装单元数（含未加载） */
    private int total;

    private int running;

    private int stopped;

    private int failed;

    /** 自启已启用 */
    private int enabled;

    /** 自启已禁用 */
    private int disabled;

    /** 已掩蔽 */
    private int masked;

    /** 别名单元数 */
    private int alias;

    /** 宿主通道是否可用；false 时前端整页降级只读 */
    private boolean hostChannelOk;

    /** 宿主 systemctl is-system-running（running / degraded / maintenance） */
    private String systemRunning;
}
