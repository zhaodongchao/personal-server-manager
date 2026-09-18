package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.entity.SysConfig;
import com.serverpanel.system.service.SysConfigService;
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
 * 参数配置接口。
 */
@RestController
@RequestMapping("/api/v1/system/config")
@RequiredArgsConstructor
public class ConfigController {

    private final SysConfigService configService;

    @SaCheckPermission("system:config:list")
    @GetMapping("/page")
    public R<PageResult<SysConfig>> page(PageQuery query,
            @RequestParam(required = false) String keyword) {
        return R.ok(configService.page(query, keyword));
    }

    @Audit(module = "system", action = "config:add")
    @SaCheckPermission("system:config:edit")
    @PostMapping
    public R<Void> create(@Valid @RequestBody SysConfig body) {
        configService.create(body);
        return R.ok();
    }

    @Audit(module = "system", action = "config:edit")
    @SaCheckPermission("system:config:edit")
    @PutMapping
    public R<Void> update(@Valid @RequestBody SysConfig body) {
        configService.update(body);
        return R.ok();
    }

    @Audit(module = "system", action = "config:delete", risky = true)
    @SaCheckPermission("system:config:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return R.ok();
    }
}
