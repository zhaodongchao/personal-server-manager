package com.serverpanel.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.core.R;
import com.serverpanel.monitor.dto.MetricFrame;
import com.serverpanel.monitor.dto.MonitorOverview;
import com.serverpanel.monitor.service.MetricsCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 监控接口。
 */
@RestController
@RequestMapping("/api/v1/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MetricsCollector metricsCollector;

    /** 系统概览 + 最新一帧 */
    @SaCheckPermission("dashboard:view")
    @GetMapping("/overview")
    public R<MonitorOverview> overview() {
        return R.ok(metricsCollector.overview());
    }

    /** 历史帧（最近 minutes 分钟，默认 60） */
    @SaCheckPermission("dashboard:view")
    @GetMapping("/history")
    public R<List<MetricFrame>> history(@RequestParam(defaultValue = "60") int minutes) {
        int safeMinutes = Math.min(Math.max(minutes, 1), 60);
        return R.ok(metricsCollector.history(Duration.ofMinutes(safeMinutes)));
    }
}
