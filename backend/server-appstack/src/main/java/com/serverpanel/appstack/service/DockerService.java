package com.serverpanel.appstack.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.serverpanel.appstack.dto.ContainerInfo;
import com.serverpanel.appstack.dto.ImageInfo;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.docker.DockerClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Docker 容器/镜像管理（通过 docker-java 走 unix socket）。
 *
 * <p>客户端统一从 {@link DockerClientProvider} 获取（惰性创建）：
 * Docker 未安装时只影响本模块调用，不会拖垮面板启动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final DockerClientProvider dockerClientProvider;

    private DockerClient client() {
        return dockerClientProvider.client();
    }

    /** Docker 是否可用 */
    public boolean available() {
        try {
            client().pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<ContainerInfo> listContainers() {
        return guard(docker -> docker.listContainersCmd().withShowAll(true).exec()
            .stream()
            .map(this::toContainer)
            .toList());
    }

    public void containerAction(String id, String action) {
        guard(docker -> {
            switch (action) {
                case "start" -> docker.startContainerCmd(id).exec();
                case "stop" -> docker.stopContainerCmd(id).exec();
                case "restart" -> docker.restartContainerCmd(id).exec();
                case "remove" -> docker.removeContainerCmd(id).withForce(true).exec();
                default -> throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "不支持的操作: " + action);
            }
            return null;
        });
    }

    public List<ImageInfo> listImages() {
        return guard(docker -> docker.listImagesCmd().exec()
            .stream()
            .map(this::toImage)
            .toList());
    }

    /** 拉取镜像（阻塞等待完成，最长 5 分钟） */
    public void pullImage(String image) {
        guard(docker -> {
            try {
                docker.pullImageCmd(image)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException(ErrorCode.ERROR.getCode(), "拉取被中断");
            }
            return null;
        });
    }

    // ==================== 转换与保护 ====================

    private ContainerInfo toContainer(Container c) {
        ContainerInfo info = new ContainerInfo();
        String full = c.getId() == null ? "" : c.getId();
        info.setId(full.length() > 12 ? full.substring(0, 12) : full);
        info.setName(c.getNames() == null || c.getNames().length == 0
            ? "-" : c.getNames()[0].replaceFirst("^/", ""));
        info.setImage(c.getImage() == null ? "-" : c.getImage());
        info.setState(c.getState() == null ? "-" : c.getState());
        info.setStatus(c.getStatus() == null ? "-" : c.getStatus());
        info.setPorts(formatPorts(c));
        return info;
    }

    private String formatPorts(Container c) {
        if (c.getPorts() == null || c.getPorts().length == 0) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (var p : c.getPorts()) {
            StringBuilder sb = new StringBuilder();
            if (p.getIp() != null && !p.getIp().isEmpty()) {
                sb.append(p.getIp()).append(':');
            }
            if (p.getPublicPort() != null) {
                sb.append(p.getPublicPort()).append("->");
            }
            sb.append(p.getPrivatePort() == null ? "?" : p.getPrivatePort());
            sb.append('/').append(p.getType() == null ? "tcp" : p.getType().toLowerCase());
            parts.add(sb.toString());
        }
        return String.join(", ", parts);
    }

    private ImageInfo toImage(Image img) {
        ImageInfo info = new ImageInfo();
        String full = img.getId() == null ? "" : img.getId().replaceFirst("^sha256:", "");
        info.setId(full.length() > 12 ? full.substring(0, 12) : full);
        info.setTag(img.getRepoTags() == null || img.getRepoTags().length == 0
            ? "<none>:<none>" : img.getRepoTags()[0]);
        info.setSize(img.getSize() == null ? 0 : img.getSize());
        info.setCreated(img.getCreated() == null ? 0 : img.getCreated());
        return info;
    }

    /** 统一异常包装：Docker 异常 → DOCKER_UNAVAILABLE */
    private <T> T guard(Function<DockerClient, T> op) {
        try {
            return op.apply(client());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("docker operation failed: {}", e.getMessage());
            throw new ServiceException(ErrorCode.DOCKER_UNAVAILABLE);
        }
    }
}
