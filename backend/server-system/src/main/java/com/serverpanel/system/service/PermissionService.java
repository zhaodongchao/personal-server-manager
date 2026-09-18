package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.common.constant.CommonConstants;
import com.serverpanel.system.dto.auth.RouteVO;
import com.serverpanel.system.entity.SysMenu;
import com.serverpanel.system.entity.SysRole;
import com.serverpanel.system.entity.SysRoleMenu;
import com.serverpanel.system.entity.SysUserRole;
import com.serverpanel.system.mapper.SysMenuMapper;
import com.serverpanel.system.mapper.SysRoleMapper;
import com.serverpanel.system.mapper.SysRoleMenuMapper;
import com.serverpanel.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限查询与菜单路由构建（Sa-Token 数据源 / 前端动态路由）。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    /** 用户角色 key 列表 */
    public List<String> getUserRoleKeys(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
            .filter(r -> Objects.equals(r.getStatus(), CommonConstants.STATUS_ENABLED))
            .map(SysRole::getRoleKey)
            .toList();
    }

    /** 用户是否超级管理员 */
    public boolean isSuperAdmin(Long userId) {
        return getUserRoleKeys(userId).contains(CommonConstants.SUPER_ADMIN_ROLE_KEY);
    }

    /**
     * 用户可见菜单（启用的 M/C 节点；F 按钮不算菜单）。
     * 超管看全部；普通用户按角色授权。
     */
    public List<SysMenu> getUserMenus(Long userId) {
        LambdaQueryWrapper<SysMenu> base = new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getStatus, CommonConstants.STATUS_ENABLED)
            .in(SysMenu::getMenuType, CommonConstants.MENU_TYPE_DIR, CommonConstants.MENU_TYPE_MENU)
            .orderByAsc(SysMenu::getSort);
        if (isSuperAdmin(userId)) {
            return menuMapper.selectList(base);
        }
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> menuIds = roleMenuMapper.selectList(
                new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
            .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectList(base.in(SysMenu::getId, menuIds));
    }

    /**
     * 用户权限码（按钮 perms；超管返回全部）。
     */
    public List<String> getUserPerms(Long userId) {
        LambdaQueryWrapper<SysMenu> base = new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getStatus, CommonConstants.STATUS_ENABLED)
            .isNotNull(SysMenu::getPerms)
            .ne(SysMenu::getPerms, "");
        List<SysMenu> menus = isSuperAdmin(userId)
            ? menuMapper.selectList(base)
            : getUserPermsMenus(userId);
        return menus.stream().map(SysMenu::getPerms)
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream().toList();
    }

    private List<SysMenu> getUserPermsMenus(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> menuIds = roleMenuMapper.selectList(
                new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
            .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getStatus, CommonConstants.STATUS_ENABLED)
            .in(SysMenu::getId, menuIds)
            .isNotNull(SysMenu::getPerms));
    }

    /**
     * 构建当前用户的 Vben 后端路由树。
     *
     * <p>规则：M 目录 → BasicLayout 容器；根级 C → BasicLayout 包一层布局，
     * 子页面 path 取 "{routePath}/index"；二级 C 直接挂页面组件。
     */
    public List<RouteVO> buildUserRoutes(Long userId) {
        List<SysMenu> menus = getUserMenus(userId);
        Map<Long, List<SysMenu>> byParent = menus.stream()
            .collect(Collectors.groupingBy(SysMenu::getParentId, Collectors.toList()));

        List<RouteVO> roots = new ArrayList<>();
        for (SysMenu top : byParent.getOrDefault(CommonConstants.ROOT_PARENT_ID, List.of())) {
            List<SysMenu> children = byParent.getOrDefault(top.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(m -> m.getSort() == null ? 0 : m.getSort()))
                .filter(m -> Objects.equals(m.getVisible(), 1))
                .toList();
            switch (top.getMenuType()) {
                case CommonConstants.MENU_TYPE_DIR -> roots.add(buildDirRoute(top, children));
                case CommonConstants.MENU_TYPE_MENU -> roots.add(buildSingleRoute(top));
                default -> { /* 忽略根级按钮 */ }
            }
        }
        return roots;
    }

    /** M 目录 → BasicLayout + 子菜单 */
    private RouteVO buildDirRoute(SysMenu dir, List<SysMenu> children) {
        RouteVO vo = new RouteVO();
        vo.setComponent("BasicLayout");
        vo.setPath(dir.getRoutePath());
        vo.setName(routeName(dir.getRoutePath()));
        vo.setMeta(new RouteVO.Meta(dir.getMenuName(), dir.getIcon(), dir.getSort(),
            !Objects.equals(dir.getVisible(), 1)));
        List<RouteVO> childRoutes = children.stream()
            .filter(c -> CommonConstants.MENU_TYPE_MENU.equals(c.getMenuType()))
            .map(this::buildPageRoute)
            .toList();
        vo.setChildren(childRoutes);
        if (!childRoutes.isEmpty()) {
            vo.setRedirect(childRoutes.get(0).getPath());
        }
        return vo;
    }

    /** 根级 C 菜单 → BasicLayout + 单页面子节点 */
    private RouteVO buildSingleRoute(SysMenu menu) {
        RouteVO vo = new RouteVO();
        vo.setComponent("BasicLayout");
        vo.setPath(menu.getRoutePath());
        vo.setName(routeName(menu.getRoutePath()));
        vo.setMeta(new RouteVO.Meta(menu.getMenuName(), menu.getIcon(), menu.getSort(),
            !Objects.equals(menu.getVisible(), 1)));
        RouteVO page = buildPageRoute(menu);
        // 子页面 path 加 /index 后缀，避免与父路由同名冲突
        page.setPath(menu.getRoutePath() + "/index");
        page.setName(routeName(page.getPath()));
        vo.setChildren(List.of(page));
        vo.setRedirect(page.getPath());
        return vo;
    }

    /** C 菜单 → 页面节点 */
    private RouteVO buildPageRoute(SysMenu menu) {
        RouteVO vo = new RouteVO();
        vo.setComponent(menu.getComponent());
        vo.setPath(menu.getRoutePath());
        vo.setName(routeName(menu.getRoutePath()));
        vo.setMeta(new RouteVO.Meta(menu.getMenuName(), menu.getIcon(), menu.getSort(),
            !Objects.equals(menu.getVisible(), 1)));
        return vo;
    }

    /** "/system/user" → "SystemUser" */
    private String routeName(String path) {
        if (path == null || path.isBlank()) {
            return "Route" + System.nanoTime();
        }
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isBlank() || !Character.isLetter(part.charAt(0))) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.isEmpty() ? "Route" + System.nanoTime() : sb.toString();
    }
}
