package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.entity.SysQuickNav;
import com.serverpanel.system.service.QuickNavService;
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
 * 快捷导航配置接口。
 */
@RestController
@RequestMapping("/api/v1/system/quick-nav")
@RequiredArgsConstructor
public class QuickNavController {

    private final QuickNavService quickNavService;

    @SaCheckPermission("system:quick-nav:list")
    @GetMapping("/page")
    public R<PageResult<SysQuickNav>> page(PageQuery query,
            @RequestParam(required = false) String keyword) {
        return R.ok(quickNavService.page(query, keyword));
    }

    @Audit(module = "system", action = "quick-nav:add")
    @SaCheckPermission("system:quick-nav:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody SysQuickNav body) {
        quickNavService.create(body);
        return R.ok();
    }

    @Audit(module = "system", action = "quick-nav:edit")
    @SaCheckPermission("system:quick-nav:edit")
    @PutMapping
    public R<Void> update(@Valid @RequestBody SysQuickNav body) {
        quickNavService.update(body);
        return R.ok();
    }

    @Audit(module = "system", action = "quick-nav:delete", risky = true)
    @SaCheckPermission("system:quick-nav:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        quickNavService.delete(id);
        return R.ok();
    }
}
