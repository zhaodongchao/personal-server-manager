package com.serverpanel.framework.docker;

import java.time.Duration;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

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

    /** 最大并发连接数（面板的 Docker 调用都是短命令，100 足够且不至于打爆守护进程） */
    private static final int MAX_CONNECTIONS = 100;

    /** 建连超时：守护进程不可用时快速失败，避免长时间占用调度线程 */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /** 响应超时：镜像拉取等流式接口按块刷新，30s 无数据即视为异常 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** Docker 守护进程地址（unix socket 或 tcp） */
    @Value("${serverpanel.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    /** 客户端实例（双重检查加锁，仅创建一次） */
    private volatile DockerClient client;

    /** 底层 HTTP 传输（随客户端一同创建，关闭时需显式释放） */
    private volatile DockerHttpClient httpClient;

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
                    // docker-java 3.7 已移除按 ServiceLoader 推导命令执行工厂的逻辑，
                    // 必须显式提供 DockerHttpClient，否则首次执行命令时报
                    // "dockerCmdExecFactory was not specified"
                    DockerHttpClient transport = new ApacheDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .sslConfig(config.getSSLConfig())
                            .maxConnections(MAX_CONNECTIONS)
                            .connectionTimeout(CONNECTION_TIMEOUT)
                            .responseTimeout(RESPONSE_TIMEOUT)
                            .build();
                    current = DockerClientImpl.getInstance(config, transport);
                    client = current;
                    httpClient = transport;
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

    /**
     * 应用关闭时释放 Docker 客户端与底层 HTTP 传输。
     *
     * <p>未创建过客户端时不做任何事；释放异常只记日志，不阻断关闭流程。
     */
    @PreDestroy
    public void close() {
        DockerClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                log.warn("关闭 Docker 客户端异常：{}", e.getMessage());
            }
        }
        DockerHttpClient transport = httpClient;
        httpClient = null;
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception e) {
                log.warn("关闭 Docker HTTP 传输异常：{}", e.getMessage());
            }
        }
    }
}
