package com.serverpanel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ServerPanel 启动类。
 *
 * <p>单机服务器管理面板：认证/RBAC、实时监控、文件管理、运维工具、应用栈管理。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.serverpanel.**.mapper")
public class ServerPanelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerPanelApplication.class, args);
    }
}
