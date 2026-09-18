package com.serverpanel.appstack.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Nginx 模块配置（application.yml 的 serverpanel.nginx.*）。
 *
 * <p>conf-dir 为面板代管的站点配置目录，须已包含进 nginx 主配置
 * （如 nginx.conf 里 include /etc/nginx/panel.d/*.conf）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.nginx")
public class NginxProperties {

    /** 站点配置目录 */
    private String confDir = "/etc/nginx/panel.d";

    /** nginx 可执行文件 */
    private String binary = "nginx";

    public Path confDirPath() {
        return Path.of(confDir);
    }
}
