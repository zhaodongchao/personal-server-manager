package com.serverpanel.ops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.common.core.R;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.ops.service.HostChannelService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

/**
 * 宿主执行通道能力接口。
 *
 * <p>前端在进入运维三页前先查此接口：不可用则整页降级为只读，并展示安装指引，
 * 而不是像旧实现那样静默返回空列表（那会让使用者误以为「宿主机上真的没有服务」）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/host")
@RequiredArgsConstructor
public class HostController {

    private final HostChannelService hostChannelService;

    /** 能力快照（refresh=true 可强制重探） */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/capability")
    public R<HostCapability> capability() {
        return R.ok(hostChannelService.capability());
    }

    /** 主动重探：安装完宿主代理后无需重启面板 */
    @SaCheckPermission("ops:service:list")
    @PostMapping("/probe")
    public R<HostCapability> probe() {
        return R.ok(hostChannelService.refresh());
    }
}
