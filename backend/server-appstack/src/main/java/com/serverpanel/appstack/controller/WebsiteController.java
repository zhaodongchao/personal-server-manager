package com.serverpanel.appstack.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.appstack.dto.WebsiteBody;
import com.serverpanel.appstack.entity.AppWebsite;
import com.serverpanel.appstack.service.WebsiteService;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
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
 * Nginx 网站管理接口。
 */
@RestController
@RequestMapping("/api/v1/appstack/website")
@RequiredArgsConstructor
public class WebsiteController {

    private final WebsiteService websiteService;

    @SaCheckPermission("appstack:website:list")
    @GetMapping("/page")
    public R<PageResult<AppWebsite>> page(PageQuery query,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(websiteService.page(query, keyword));
    }

    @SaCheckPermission("appstack:website:list")
    @GetMapping("/nginx-status")
    public R<Boolean> nginxStatus() {
        return R.ok(websiteService.nginxAvailable());
    }

    @SaCheckPermission("appstack:website:list")
    @GetMapping("/{id}/conf")
    public R<String> conf(@PathVariable Long id) {
        return R.ok(websiteService.confContent(id));
    }

    @Audit(module = "appstack", action = "website:add")
    @SaCheckPermission("appstack:website:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody WebsiteBody body) {
        websiteService.create(body);
        return R.ok();
    }

    @Audit(module = "appstack", action = "website:edit", risky = true)
    @SaCheckPermission("appstack:website:edit")
    @PutMapping
    public R<Void> update(@Valid @RequestBody WebsiteBody body) {
        websiteService.update(body);
        return R.ok();
    }

    @Audit(module = "appstack", action = "website:toggle", risky = true)
    @SaCheckPermission("appstack:website:edit")
    @PutMapping("/{id}/status/{status}")
    public R<Void> toggle(@PathVariable Long id, @PathVariable Integer status) {
        websiteService.toggle(id, status);
        return R.ok();
    }

    @Audit(module = "appstack", action = "website:delete", risky = true)
    @SaCheckPermission("appstack:website:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        websiteService.delete(id);
        return R.ok();
    }
}
