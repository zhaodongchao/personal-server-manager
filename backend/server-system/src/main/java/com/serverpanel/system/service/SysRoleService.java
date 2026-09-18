package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.dto.RoleBody;
import com.serverpanel.system.entity.SysRole;
import com.serverpanel.system.entity.SysRoleMenu;
import com.serverpanel.system.entity.SysUserRole;
import com.serverpanel.system.mapper.SysRoleMapper;
import com.serverpanel.system.mapper.SysRoleMenuMapper;
import com.serverpanel.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色管理 Service。
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PageResult<SysRole> page(PageQuery query, String roleName) {
        Page<SysRole> page = roleMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysRole>()
                .like(roleName != null && !roleName.isBlank(), SysRole::getRoleName, roleName)
                .orderByAsc(SysRole::getSort));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 全部启用角色（用户管理选择器用） */
    public List<SysRole> listEnabled() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
            .eq(SysRole::getStatus, 1)
            .orderByAsc(SysRole::getSort));
    }

    public List<Long> getMenuIds(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId))
            .stream().map(SysRoleMenu::getMenuId).toList();
    }

    @Transactional
    public void create(RoleBody body) {
        checkUnique(null, body.getRoleKey());
        SysRole role = new SysRole();
        copy(body, role);
        roleMapper.insert(role);
        if (body.getMenuIds() != null) {
            assignMenus(role.getId(), body.getMenuIds());
        }
    }

    @Transactional
    public void update(Long id, RoleBody body) {
        SysRole role = requireRole(id);
        if (role.getRoleKey().equals("admin") && !body.getRoleKey().equals("admin")) {
            throw new ServiceException(ErrorCode.BUILTIN_DATA);
        }
        checkUnique(id, body.getRoleKey());
        copy(body, role);
        roleMapper.updateById(role);
        if (body.getMenuIds() != null) {
            assignMenus(id, body.getMenuIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (role.getRoleKey().equals("admin")) {
            throw new ServiceException(ErrorCode.BUILTIN_DATA);
        }
        if (userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, id)) > 0) {
            throw new ServiceException(ErrorCode.ROLE_IN_USE);
        }
        roleMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
            .eq(SysRoleMenu::getRoleId, id));
    }

    /** 重绑角色菜单 */
    public void assignMenus(Long roleId, List<Long> menuIds) {
        requireRole(roleId);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
            .eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                SysRoleMenu rel = new SysRoleMenu();
                rel.setRoleId(roleId);
                rel.setMenuId(menuId);
                roleMenuMapper.insert(rel);
            }
        }
    }

    private void copy(RoleBody body, SysRole role) {
        role.setRoleName(body.getRoleName());
        role.setRoleKey(body.getRoleKey());
        role.setSort(body.getSort() == null ? 0 : body.getSort());
        role.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        role.setRemark(body.getRemark());
    }

    private void checkUnique(Long excludeId, String roleKey) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
            .eq(SysRole::getRoleKey, roleKey)
            .ne(excludeId != null, SysRole::getId, excludeId);
        if (roleMapper.selectCount(wrapper) > 0) {
            throw new ServiceException(ErrorCode.ROLE_EXISTS);
        }
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new ServiceException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }
}
