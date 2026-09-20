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

    /** Web 管理端口（-1 表示未知） */
    private int port;

    /** Web 访问路径前缀 */
    private String path;

    /** 是否正在运行 */
    private boolean running;
}
