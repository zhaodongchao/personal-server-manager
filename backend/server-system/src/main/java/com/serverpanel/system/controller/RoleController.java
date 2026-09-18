package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.RoleBody;
import com.serverpanel.system.entity.SysRole;
import com.serverpanel.system.service.SysRoleService;
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

import java.util.List;

/**
 * 角色管理接口。
 */
@RestController
@RequestMapping("/api/v1/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleService roleService;

    @SaCheckPermission("system:role:list")
    @GetMapping("/page")
    public R<PageResult<SysRole>> page(PageQuery query,
            @RequestParam(required = false) String roleName) {
        return R.ok(roleService.page(query, roleName));
    }

    /** 启用角色列表（用户管理选择器） */
    @SaCheckPermission("system:role:list")
    @GetMapping("/all")
    public R<List<SysRole>> listAll() {
        return R.ok(roleService.listEnabled());
    }

    /** 角色已授权菜单 */
    @SaCheckPermission("system:role:list")
    @GetMapping("/{id}/menu-ids")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(roleService.getMenuIds(id));
    }

    @Audit(module = "system", action = "role:add")
    @SaCheckPermission("system:role:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody RoleBody body) {
        roleService.create(body);
        return R.ok();
    }

    @Audit(module = "system", action = "role:edit")
    @SaCheckPermission("system:role:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody RoleBody body) {
        roleService.update(id, body);
        return R.ok();
    }

    @Audit(module = "system", action = "role:delete", risky = true)
    @SaCheckPermission("system:role:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.ok();
    }
}
