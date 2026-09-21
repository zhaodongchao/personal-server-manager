package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.CronJobBody;
import com.serverpanel.ops.dto.CronPreviewBody;
import com.serverpanel.ops.dto.CronPreviewVO;
import com.serverpanel.ops.dto.CronStatusBody;
import com.serverpanel.ops.dto.CronSummaryVO;
import com.serverpanel.ops.dto.CronWhitelistVO;
import com.serverpanel.ops.entity.OpsCronJob;
import com.serverpanel.ops.entity.OpsCronLog;
import com.serverpanel.ops.service.CronJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 计划任务接口。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/cron")
@RequiredArgsConstructor
public class CronJobController {

    private final CronJobService cronJobService;

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/page")
    public R<PageResult<OpsCronJob>> page(PageQuery query,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String lastResult) {
        return R.ok(cronJobService.page(query, keyword, status, lastResult));
    }

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/summary")
    public R<CronSummaryVO> summary() {
        return R.ok(cronJobService.summary());
    }

    /**
     * cron 表达式校验 + 预览（未来 N 次执行时间与人话描述）。
     *
     * <p>独立成接口而不放在保存时校验：表单需要「边填边看」，
     * 且校验失败要能返回具体原因而不是一个笼统的 400。
     */
    @SaCheckPermission("ops:cron:list")
    @PostMapping("/preview")
    public R<CronPreviewVO> preview(@Valid @RequestBody CronPreviewBody body) {
        return R.ok(cronJobService.preview(body));
    }

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/commands/whitelist")
    public R<CronWhitelistVO> whitelist() {
        return R.ok(cronJobService.whitelist());
    }

    @Audit(module = "ops", action = "cron:add")
    @SaCheckPermission("ops:cron:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody CronJobBody body) {
        cronJobService.create(body);
        return R.ok();
    }

    @Audit(module = "ops", action = "cron:edit")
    @SaCheckPermission("ops:cron:edit")
    @PutMapping
    public R<Void> update(@Valid @RequestBody CronJobBody body) {
        cronJobService.update(body);
        return R.ok();
    }

    @Audit(module = "ops", action = "cron:delete", risky = true)
    @SaCheckPermission("ops:cron:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        cronJobService.delete(id);
        return R.ok();
    }

    @Audit(module = "ops", action = "cron:batch-delete", risky = true)
    @SaCheckPermission("ops:cron:delete")
    @DeleteMapping("/batch")
    public R<Integer> batchDelete(@RequestBody List<Long> ids) {
        return R.ok(cronJobService.batchDelete(ids));
    }

    /** 列表内快速启停；启用时会立即重算下次执行时间 */
    @Audit(module = "ops", action = "cron:status")
    @SaCheckPermission("ops:cron:status")
    @PatchMapping("/{id}/status")
    public R<Void> setStatus(@PathVariable Long id, @Valid @RequestBody CronStatusBody body) {
        cronJobService.setStatus(id, body.getStatus());
        return R.ok();
    }

    /**
     * 立即执行，返回本次日志 ID；前端据此轮询单条日志直到 finishedAt 非空。
     *
     * <p>并发策略为 skip 且上一次尚未结束时，不会报错，而是照常返回日志 ID——
     * 本次执行会被记为「已跳过」，前端在日志里能看到原因。
     */
    @Audit(module = "ops", action = "cron:run", risky = true)
    @SaCheckPermission("ops:cron:run")
    @PostMapping("/{id}/run")
    public R<Long> run(@PathVariable Long id) {
        return R.ok(cronJobService.runNow(id));
    }

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/{id}/logs")
    public R<PageResult<OpsCronLog>> logs(@PathVariable Long id, PageQuery query,
                                          @RequestParam(required = false) String result) {
        return R.ok(cronJobService.logs(id, query, result));
    }

    /** 单条执行日志详情（供前端轮询执行进度） */
    @SaCheckPermission("ops:cron:list")
    @GetMapping("/{id}/logs/{logId}")
    public R<OpsCronLog> logDetail(@PathVariable Long id, @PathVariable Long logId) {
        return R.ok(cronJobService.logDetail(id, logId));
    }

    /** 下载单次执行的完整输出 */
    @SaCheckPermission("ops:cron:list")
    @GetMapping("/{id}/logs/{logId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long logId) {
        OpsCronLog row = cronJobService.logDetail(id, logId);
        String text = row.getOutput() == null ? "" : row.getOutput();
        String filename = URLEncoder.encode(
                "cron-" + id + "-" + logId + ".log", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.TEXT_PLAIN)
                .body(text.getBytes(StandardCharsets.UTF_8));
    }

    @Audit(module = "ops", action = "cron:log-clean", risky = true)
    @SaCheckPermission("ops:cron:log-clean")
    @DeleteMapping("/{id}/logs")
    public R<Integer> clearLogs(@PathVariable Long id) {
        return R.ok(cronJobService.clearLogs(id));
    }
}
