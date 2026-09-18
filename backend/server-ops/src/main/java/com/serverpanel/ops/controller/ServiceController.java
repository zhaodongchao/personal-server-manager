package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.ServiceInfo;
import com.serverpanel.ops.service.ServiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * systemd 服务管理接口。
 */
@RestController
@RequestMapping("/api/v1/ops/service")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;

    @SaCheckPermission("ops:service:list")
    @GetMapping("/list")
    public R<List<ServiceInfo>> list(@RequestParam(required = false) String keyword) {
        return R.ok(serviceService.list(keyword));
    }

    @SaCheckPermission("ops:service:list")
    @GetMapping("/{name}")
    public R<String> detail(@PathVariable String name) {
        return R.ok(serviceService.detail(name));
    }

    @Audit(module = "ops", action = "service:manage", risky = true)
    @SaCheckPermission("ops:service:manage")
    @PostMapping("/{name}/{action}")
    public R<Void> action(@PathVariable String name, @PathVariable String action) {
        serviceService.action(name, action);
        return R.ok();
    }
}
