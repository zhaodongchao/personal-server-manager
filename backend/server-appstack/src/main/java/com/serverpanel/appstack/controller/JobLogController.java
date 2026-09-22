package com.serverpanel.appstack.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.appstack.dto.ClearLogBody;
import com.serverpanel.appstack.dto.JobLogQuery;
import com.serverpanel.appstack.entity.AppJobLog;
import com.serverpanel.appstack.service.JobLogService;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 定时任务日志接口（应用栈 → 定时任务日志）。
 *
 * <p>列表刻意把「调度结果」与「执行耗时/结果」分开返回：{@code triggerCode=500}
 * 表示到点了没派发出去，{@code handleCode=500} 表示派发了但执行失败 ——
 * 这两类失败的排查方向完全不同，糊成一列就丢掉了整套日志模型的价值。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@RestController
@RequestMapping("/api/v1/appstack/job-log")
@RequiredArgsConstructor
public class JobLogController {

    private final JobLogService logService;

    @SaCheckPermission("appstack:joblog:list")
    @GetMapping("/page")
    public R<PageResult<AppJobLog>> page(JobLogQuery query) {
        return R.ok(logService.page(query));
    }

    @SaCheckPermission("appstack:joblog:list")
    @GetMapping("/statistics")
    public R<Map<String, Object>> statistics() {
        return R.ok(logService.statistics());
    }

    @SaCheckPermission("appstack:joblog:list")
    @GetMapping("/retention")
    public R<Map<String, Object>> retention() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retentionDays", logService.retentionDays());
        return R.ok(payload);
    }

    @SaCheckPermission("appstack:joblog:list")
    @GetMapping("/{id}")
    public R<AppJobLog> detail(@PathVariable Long id) {
        return R.ok(logService.detail(id));
    }

    @SaCheckPermission("appstack:joblog:list")
    @GetMapping("/{id}/output")
    public R<String> output(@PathVariable Long id) {
        return R.ok(logService.output(id));
    }

    @Audit(module = "appstack", action = "joblog:clear", risky = true)
    @SaCheckPermission("appstack:joblog:clear")
    @DeleteMapping("/clear")
    public R<Map<String, Object>> clear(@Valid @RequestBody ClearLogBody body) {
        int deleted = logService.clear(body);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deleted", deleted);
        return R.ok(payload);
    }
}
