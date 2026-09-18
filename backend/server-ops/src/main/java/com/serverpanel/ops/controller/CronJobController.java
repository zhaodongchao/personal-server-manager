package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.CronJobBody;
import com.serverpanel.ops.entity.OpsCronJob;
import com.serverpanel.ops.entity.OpsCronLog;
import com.serverpanel.ops.service.CronJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划任务接口。
 */
@RestController
@RequestMapping("/api/v1/ops/cron")
@RequiredArgsConstructor
public class CronJobController {

    private final CronJobService cronJobService;

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/page")
    public R<PageResult<OpsCronJob>> page(PageQuery query,
            @RequestParam(required = false) String keyword) {
        return R.ok(cronJobService.page(query, keyword));
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

    @Audit(module = "ops", action = "cron:run", risky = true)
    @SaCheckPermission("ops:cron:run")
    @PostMapping("/{id}/run")
    public R<Long> run(@PathVariable Long id) {
        return R.ok(cronJobService.runNow(id));
    }

    @SaCheckPermission("ops:cron:list")
    @GetMapping("/{id}/logs")
    public R<PageResult<OpsCronLog>> logs(@PathVariable Long id, PageQuery query) {
        return R.ok(cronJobService.logs(id, query));
    }
}
