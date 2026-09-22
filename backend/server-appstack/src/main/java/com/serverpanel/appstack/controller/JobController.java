package com.serverpanel.appstack.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.appstack.dto.CronPreviewVO;
import com.serverpanel.appstack.dto.CronValidateBody;
import com.serverpanel.appstack.dto.ExecutorBody;
import com.serverpanel.appstack.dto.ExecutorVO;
import com.serverpanel.appstack.dto.JobActionBody;
import com.serverpanel.appstack.dto.JobBody;
import com.serverpanel.appstack.dto.JobQuery;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.service.JobService;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 定时任务管理接口（应用栈 → 定时任务管理）。
 *
 * <p>执行器管理合并在本控制器下（{@code /executor/**}），因为它与任务是同一张表单的
 * 上下游关系（任务必须选执行器），拆到两个控制器只会让「谁能改执行器」的权限边界变模糊。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@RestController
@RequestMapping("/api/v1/appstack/job")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    // ==================== 查询与元数据 ====================

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/page")
    public R<PageResult<AppJob>> page(JobQuery query) {
        return R.ok(jobService.page(query));
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(jobService.stats());
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/handlers")
    public R<List<JobHandler.HandlerSchema>> handlers() {
        return R.ok(jobService.handlers());
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/commands")
    public R<List<Map<String, Object>>> commands() {
        return R.ok(jobService.commands());
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(jobService.options());
    }

    @SaCheckPermission("appstack:job:list")
    @PostMapping("/cron/validate")
    public R<CronPreviewVO> validateCron(@Valid @RequestBody CronValidateBody body) {
        return R.ok(jobService.validateCron(body.getCronExpr()));
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/{id}")
    public R<AppJob> detail(@PathVariable Long id) {
        return R.ok(jobService.detail(id));
    }

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/{id}/next-times")
    public R<List<String>> nextTimes(@PathVariable Long id,
                                     @RequestParam(defaultValue = "5") int n) {
        return R.ok(jobService.nextTimes(id, n));
    }

    // ==================== 任务写操作 ====================

    @Audit(module = "appstack", action = "job:save", risky = true)
    @SaCheckPermission("appstack:job:save")
    @PostMapping
    public R<AppJob> create(@Valid @RequestBody JobBody body) {
        return R.ok(jobService.create(body));
    }

    @Audit(module = "appstack", action = "job:save", risky = true)
    @SaCheckPermission("appstack:job:save")
    @PutMapping("/{id}")
    public R<AppJob> update(@PathVariable Long id, @Valid @RequestBody JobBody body) {
        return R.ok(jobService.update(id, body));
    }

    @Audit(module = "appstack", action = "job:delete", risky = true)
    @SaCheckPermission("appstack:job:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestBody(required = false) JobActionBody body) {
        jobService.delete(id, body == null ? null : body.getConfirm());
        return R.ok();
    }

    @Audit(module = "appstack", action = "job:copy", risky = true)
    @SaCheckPermission("appstack:job:save")
    @PostMapping("/{id}/copy")
    public R<AppJob> copy(@PathVariable Long id) {
        return R.ok(jobService.copy(id));
    }

    @Audit(module = "appstack", action = "job:toggle", risky = true)
    @SaCheckPermission("appstack:job:toggle")
    @PostMapping("/{id}/enable")
    public R<AppJob> enable(@PathVariable Long id) {
        return R.ok(jobService.toggle(id, true));
    }

    @Audit(module = "appstack", action = "job:toggle", risky = true)
    @SaCheckPermission("appstack:job:toggle")
    @PostMapping("/{id}/disable")
    public R<AppJob> disable(@PathVariable Long id) {
        return R.ok(jobService.toggle(id, false));
    }

    @Audit(module = "appstack", action = "job:run", risky = true)
    @SaCheckPermission("appstack:job:run")
    @PostMapping("/{id}/run")
    public R<Map<String, Object>> run(@PathVariable Long id,
                                      @RequestBody(required = false) JobActionBody body) {
        Long logId = jobService.run(id, body == null ? null : body.getParamOverride());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("logId", logId == null ? null : String.valueOf(logId));
        payload.put("message", "已派发执行，请到「定时任务日志」查看结果");
        return R.ok(payload);
    }

    @Audit(module = "appstack", action = "job:stop", risky = true)
    @SaCheckPermission("appstack:job:stop")
    @PostMapping("/{id}/stop")
    public R<Map<String, Object>> stop(@PathVariable Long id,
                                       @RequestBody(required = false) JobActionBody body) {
        return R.ok(jobService.stop(id, body == null ? null : body.getConfirm()));
    }

    // ==================== 执行器 ====================

    @SaCheckPermission("appstack:job:list")
    @GetMapping("/executor/list")
    public R<List<ExecutorVO>> executors() {
        return R.ok(jobService.executors());
    }

    @Audit(module = "appstack", action = "job:executor", risky = true)
    @SaCheckPermission("appstack:job:executor")
    @PostMapping("/executor")
    public R<ExecutorVO> createExecutor(@Valid @RequestBody ExecutorBody body) {
        return R.ok(jobService.createExecutor(body));
    }

    @Audit(module = "appstack", action = "job:executor", risky = true)
    @SaCheckPermission("appstack:job:executor")
    @PutMapping("/executor/{id}")
    public R<ExecutorVO> updateExecutor(@PathVariable Long id,
                                        @Valid @RequestBody ExecutorBody body) {
        return R.ok(jobService.updateExecutor(id, body));
    }

    @Audit(module = "appstack", action = "job:executor", risky = true)
    @SaCheckPermission("appstack:job:executor")
    @DeleteMapping("/executor/{id}")
    public R<Void> deleteExecutor(@PathVariable Long id,
                                  @RequestBody(required = false) JobActionBody body) {
        jobService.deleteExecutor(id, body == null ? null : body.getConfirm());
        return R.ok();
    }

    @SaCheckPermission("appstack:job:executor")
    @PostMapping("/executor/{id}/test")
    public R<ExecutorVO> testExecutor(@PathVariable Long id) {
        return R.ok(jobService.testExecutor(id));
    }
}
