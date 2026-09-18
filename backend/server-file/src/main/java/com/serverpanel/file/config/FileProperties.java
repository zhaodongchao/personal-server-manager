package com.serverpanel.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件模块配置（application.yml 的 serverpanel.file.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.file")
public class FileProperties {

    /** 文件管理根目录白名单（逗号分隔，自动绑定为 List） */
    private List<String> roots = List.of("/www", "/srv", "/var/www");

    /** 回收站物理目录（面板私有，独立于白名单） */
    private String trashDir = "/var/serverpanel/trash";

    /** 回收站保留天数，过期自动清理 */
    private int trashRetainDays = 7;
}
