package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.UserBody;
import com.serverpanel.system.entity.SysUser;
import com.serverpanel.system.service.SysUserService;
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
 * 用户管理接口。
 */
@RestController
@RequestMapping("/api/v1/system/user")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService userService;

    @SaCheckPermission("system:user:list")
    @GetMapping("/page")
    public R<PageResult<SysUser>> page(PageQuery query,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status) {
        return R.ok(userService.page(query, username, status));
    }

    @SaCheckPermission("system:user:list")
    @GetMapping("/{id}")
    public R<SysUser> get(@PathVariable Long id) {
        return R.ok(userService.get(id));
    }

    /** 当前用户已绑定的角色 */
    @SaCheckPermission("system:user:list")
    @GetMapping("/{id}/role-ids")
    public R<List<Long>> roleIds(@PathVariable Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    @Audit(module = "system", action = "user:add")
    @SaCheckPermission("system:user:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody UserBody body) {
        userService.create(body);
        return R.ok();
    }

    @Audit(module = "system", action = "user:edit")
    @SaCheckPermission("system:user:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserBody body) {
        userService.update(id, body);
        return R.ok();
    }

    @Audit(module = "system", action = "user:delete", risky = true)
    @SaCheckPermission("system:user:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }
}
