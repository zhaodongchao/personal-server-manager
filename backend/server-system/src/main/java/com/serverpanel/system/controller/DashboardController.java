package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.dashboard.QuickService;
import com.serverpanel.system.dto.dashboard.SshLoginInfo;
import com.serverpanel.system.dto.dashboard.VisitSource;
import com.serverpanel.system.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作台（dashboard/workspace）聚合接口。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 最近 10 次 SSH 登录成功记录 */
    @SaCheckPermission("dashboard:view")
    @GetMapping("/ssh-logins")
    public R<List<SshLoginInfo>> sshLogins() {
        return R.ok(dashboardService.sshLogins(10));
    }

    /** 快捷导航：运行中的已知 Web 服务 */
    @SaCheckPermission("dashboard:view")
    @GetMapping("/services")
    public R<List<QuickService>> services() {
        return R.ok(dashboardService.quickServices());
    }

    /** 访问来源：登录成功 IP 按地区聚合 */
    @SaCheckPermission("dashboard:view")
    @GetMapping("/visit-sources")
    public R<List<VisitSource>> visitSources() {
        return R.ok(dashboardService.visitSources());
    }
}
