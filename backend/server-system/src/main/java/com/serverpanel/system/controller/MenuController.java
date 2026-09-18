package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.MenuBody;
import com.serverpanel.system.dto.MenuVO;
import com.serverpanel.system.service.SysMenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单管理接口。
 */
@RestController
@RequestMapping("/api/v1/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final SysMenuService menuService;

    @SaCheckPermission("system:menu:list")
    @GetMapping("/tree")
    public R<List<MenuVO>> tree() {
        return R.ok(menuService.tree());
    }

    @Audit(module = "system", action = "menu:add")
    @SaCheckPermission("system:menu:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody MenuBody body) {
        menuService.create(body);
        return R.ok();
    }

    @Audit(module = "system", action = "menu:edit")
    @SaCheckPermission("system:menu:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody MenuBody body) {
        menuService.update(id, body);
        return R.ok();
    }

    @Audit(module = "system", action = "menu:delete", risky = true)
    @SaCheckPermission("system:menu:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return R.ok();
    }
}
