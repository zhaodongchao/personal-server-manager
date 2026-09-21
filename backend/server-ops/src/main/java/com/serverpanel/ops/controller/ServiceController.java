package com.serverpanel.ops.controller;

import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.ServiceActionBody;
import com.serverpanel.ops.dto.ServiceActionResultVO;
import com.serverpanel.ops.dto.ServiceBatchBody;
import com.serverpanel.ops.dto.ServiceBatchResultVO;
import com.serverpanel.ops.dto.ServiceDetailVO;
import com.serverpanel.ops.dto.ServiceSummaryVO;
import com.serverpanel.ops.dto.ServiceVO;
import com.serverpanel.ops.dto.SysLogLine;
import com.serverpanel.ops.service.ServiceService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * systemd 服务管理接口。
 *
 * <p>权限分层：读 {@code ops:service:list}；日志 {@code ops:service:log}；
 * 单/批量动作 {@code ops:service:manage} / {@code ops:service:batch}；
 * 危险动作（保护清单内停止、mask/unmask）额外要求 {@code ops:service:danger}。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/service")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;

    /** 分页列表（推荐） */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/page")
    public R<PageResult<ServiceVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String active,
            @RequestParam(required = false) String unitFileState,
            @RequestParam(required = false) Boolean failedOnly,
            @RequestParam(required = false, defaultValue = "false") Boolean includeAlias,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return R.ok(serviceService.page(keyword, active, unitFileState, failedOnly,
                includeAlias, pageNum, pageSize));
    }

    /** 全量列表（兼容旧前端，已弃用） */
    @Deprecated
    @SaCheckPermission("ops:service:list")
    @GetMapping("/list")
    public R<List<ServiceVO>> list(@RequestParam(required = false) String keyword) {
        return R.ok(serviceService.list(keyword));
    }

    /** 失败单元聚合 */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/failed")
    public R<List<ServiceVO>> failed() {
        return R.ok(serviceService.failed());
    }

    /** 总览统计 */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/summary")
    public R<ServiceSummaryVO> summary() {
        return R.ok(serviceService.summary());
    }

    /** 保护清单（前端提前标识） */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/protected")
    public R<Set<String>> protectedUnits() {
        return R.ok(serviceService.protectedUnits());
    }

    /** 强制刷新快照 */
    @SaCheckPermission("ops:service:list")
    @PostMapping("/refresh")
    public R<Void> refresh() {
        serviceService.refresh();
        return R.ok();
    }

    /** 全局 daemon-reload */
    @Audit(module = "ops", action = "service:daemon-reload", risky = true)
    @SaCheckPermission("ops:service:manage")
    @PostMapping("/daemon-reload")
    public R<Void> daemonReload() {
        serviceService.daemonReload();
        return R.ok();
    }

    /** 批量操作 */
    @Audit(module = "ops", action = "service:batch", risky = true)
    @SaCheckPermission("ops:service:batch")
    @PostMapping("/batch")
    public R<ServiceBatchResultVO> batch(@Valid @RequestBody ServiceBatchBody body) {
        return R.ok(serviceService.batch(body));
    }

    /** 旧接口：原文状态（保留兼容） */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/{name}")
    public R<String> legacyDetail(@PathVariable String name) {
        return R.ok(serviceService.detail(name).getRawStatus());
    }

    /** 结构化详情 */
    @SaCheckPermission("ops:service:list")
    @GetMapping("/{name}/detail")
    public R<ServiceDetailVO> detail(@PathVariable String name) {
        return R.ok(serviceService.detail(name));
    }

    /** 服务日志 */
    @SaCheckPermission("ops:service:log")
    @GetMapping("/{name}/logs")
    public R<List<SysLogLine>> logs(
            @PathVariable String name,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer minLevel) {
        return R.ok(serviceService.logs(name, lines, since, until, keyword, minLevel));
    }

    /** 服务操作（带 options） */
    @Audit(module = "ops", action = "service:action", risky = true)
    @SaCheckPermission("ops:service:manage")
    @PostMapping("/{name}/action")
    public R<ServiceActionResultVO> action(@PathVariable String name,
                                          @Valid @RequestBody ServiceActionBody body) {
        return R.ok(serviceService.action(name, body));
    }

    /** 旧接口：动作写在路径上（保留兼容，等价于 action 不带 options） */
    @Audit(module = "ops", action = "service:action-legacy", risky = true)
    @SaCheckPermission("ops:service:manage")
    @PostMapping("/{name}/{action}")
    public R<ServiceActionResultVO> legacyAction(@PathVariable String name,
                                                 @PathVariable String action) {
        ServiceActionBody body = new ServiceActionBody();
        body.setAction(action);
        return R.ok(serviceService.action(name, body));
    }
}
