package com.serverpanel.appstack.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面板代管 MySQL 的管理连接配置（application.yml 的 serverpanel.mysql-admin.*）。
 *
 * <p>面板用该高权限账号创建/删除业务库与账号、执行备份恢复；
 * 各业务库的应用账号密码不入库，仅创建时返回一次。
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.mysql-admin")
public class MysqlAdminProperties {

    private String host = "localhost";

    private int port = 3306;

    private String username = "root";

    private String password = "";

    /** 备份文件存放目录 */
    private String backupDir = "/var/serverpanel/backups";

    /** mysqldump 可执行文件 */
    private String mysqldumpBinary = "mysqldump";

    /** mysql 可执行文件 */
    private String mysqlBinary = "mysql";
}
