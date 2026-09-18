package com.serverpanel.appstack.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.appstack.dto.ContainerInfo;
import com.serverpanel.appstack.dto.ImageInfo;
import com.serverpanel.appstack.dto.PullImageBody;
import com.serverpanel.appstack.service.DockerService;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Docker 管理接口。
 */
@RestController
@RequestMapping("/api/v1/appstack/docker")
@RequiredArgsConstructor
public class DockerController {

    private final DockerService dockerService;

    @SaCheckPermission("appstack:docker:list")
    @GetMapping("/containers")
    public R<List<ContainerInfo>> containers() {
        return R.ok(dockerService.listContainers());
    }

    @Audit(module = "appstack", action = "docker:container-action", risky = true)
    @SaCheckPermission("appstack:docker:manage")
    @PostMapping("/containers/{id}/{action}")
    public R<Void> containerAction(@PathVariable String id, @PathVariable String action) {
        dockerService.containerAction(id, action);
        return R.ok();
    }

    @SaCheckPermission("appstack:docker:list")
    @GetMapping("/images")
    public R<List<ImageInfo>> images() {
        return R.ok(dockerService.listImages());
    }

    @Audit(module = "appstack", action = "docker:pull-image", risky = true)
    @SaCheckPermission("appstack:docker:manage")
    @PostMapping("/images/pull")
    public R<Void> pullImage(@Valid @RequestBody PullImageBody body) {
        dockerService.pullImage(body.getImage());
        return R.ok();
    }
}
