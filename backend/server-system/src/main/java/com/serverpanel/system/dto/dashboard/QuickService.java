package com.serverpanel.system.dto.dashboard;

import lombok.Data;

/**
 * 工作台 - 快捷导航服务。
 */
@Data
public class QuickService {

    /** systemd 服务名（如 jenkins.service） */
    private String name;

    /** 展示名（如 Jenkins） */
    private String displayName;

    /** 访问域名 / 主机，留空则用面板当前访问域名 */
    private String domain;

    /** 是否走 https（1 https 0 http） */
    private int https;

    /** Web 管理端口（-1 表示未知） */
    private int port;

    /** Web 访问路径前缀 */
    private String path;

    /** 前端图标（如 lucide:gitlab） */
    private String icon;

    /** 是否正在运行 */
    private boolean running;
}
