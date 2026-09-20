package com.serverpanel.framework.docker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;

import lombok.extern.slf4j.Slf4j;

/**
 * Docker 客户端提供者（全局唯一，惰性创建）。
 *
 * <p>面板的 Docker 能力（容器 / 镜像 / 网络）统一从这里取客户端，避免各业务模块
 * 各自持有连接配置与独立客户端实例。客户端按需惰性创建：Docker 未安装或 socket
 * 不可用时只影响调用方，不会拖垮面板启动。
 *
 * @author zhaodc
 * @since 2026-09-20
 */
@Slf4j
@Component
public class DockerClientProvider {

    /** Docker 守护进程地址（unix socket 或 tcp） */
    @Value("${serverpanel.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    /** 客户端实例（双重检查加锁，仅创建一次） */
    private volatile DockerClient client;

    /**
     * 获取 Docker 客户端（首次调用时创建，此后复用）。
     *
     * @return Docker 客户端；socket 不可用时不抛异常，由具体命令调用时暴露错误
     */
    public DockerClient client() {
        DockerClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                            .withDockerHost(dockerHost)
                            .build();
                    current = DockerClientImpl.getInstance(config);
                    client = current;
                    log.info("初始化 Docker 客户端：{}", dockerHost);
                }
            }
        }
        return current;
    }

    /**
     * Docker 守护进程是否可用（ping）。
     *
     * @return 可用为 true；未安装 / 无权限 / socket 不可达为 false
     */
    public boolean available() {
        try {
            client().pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
