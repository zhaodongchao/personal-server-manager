package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.dto.MenuBody;
import com.serverpanel.system.dto.MenuVO;
import com.serverpanel.system.entity.SysMenu;
import com.serverpanel.system.entity.SysRoleMenu;
import com.serverpanel.system.mapper.SysMenuMapper;
import com.serverpanel.system.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单管理 Service。
 */
@Service
@RequiredArgsConstructor
public class SysMenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    /** 全量菜单树（管理页展示，含按钮） */
    public List<MenuVO> tree() {
        List<SysMenu> all = menuMapper.selectList(
            new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSort));
        return buildTree(all, 0L);
    }

    private List<MenuVO> buildTree(List<SysMenu> all, Long parentId) {
        Map<Long, List<SysMenu>> byParent = all.stream()
            .collect(Collectors.groupingBy(SysMenu::getParentId));
        return byParent.getOrDefault(parentId, List.of()).stream()
            .map(m -> {
                MenuVO vo = toVO(m);
                vo.setChildren(buildTree(all, m.getId()));
                return vo;
            }).toList();
    }

    private MenuVO toVO(SysMenu menu) {
        MenuVO vo = new MenuVO();
        vo.setId(menu.getId());
        vo.setParentId(menu.getParentId());
        vo.setMenuName(menu.getMenuName());
        vo.setMenuType(menu.getMenuType());
        vo.setRoutePath(menu.getRoutePath());
        vo.setComponent(menu.getComponent());
        vo.setPerms(menu.getPerms());
        vo.setIcon(menu.getIcon());
        vo.setSort(menu.getSort());
        vo.setVisible(menu.getVisible());
        vo.setStatus(menu.getStatus());
        return vo;
    }

    public void create(MenuBody body) {
        validateParent(body.getParentId());
        SysMenu menu = new SysMenu();
        copy(body, menu);
        menuMapper.insert(menu);
    }

    public void update(Long id, MenuBody body) {
        SysMenu menu = requireMenu(id);
        if (body.getParentId().equals(id)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "父菜单不能是自己");
        }
        copy(body, menu);
        menuMapper.updateById(menu);
        // updateById 走 MyBatis-Plus 默认的 NOT_NULL 策略，会跳过 null 字段 ——
        // 按类型清理掉的那些列必须再显式置空，否则「按钮带着路由地址」清不掉。
        LambdaUpdateWrapper<SysMenu> clear = new LambdaUpdateWrapper<SysMenu>()
            .eq(SysMenu::getId, id);
        boolean dirty = false;
        if (menu.getIcon() == null) {
            clear.set(SysMenu::getIcon, null);
            dirty = true;
        }
        if (menu.getRoutePath() == null) {
            clear.set(SysMenu::getRoutePath, null);
            dirty = true;
        }
        if (menu.getComponent() == null) {
            clear.set(SysMenu::getComponent, null);
            dirty = true;
        }
        if (menu.getPerms() == null) {
            clear.set(SysMenu::getPerms, null);
            dirty = true;
        }
        if (dirty) {
            menuMapper.update(null, clear);
        }
    }

    public void delete(Long id) {
        requireMenu(id);
        if (menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getParentId, id)) > 0) {
            throw new ServiceException(ErrorCode.MENU_HAS_CHILDREN);
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
            .eq(SysRoleMenu::getMenuId, id));
        menuMapper.deleteById(id);
    }

    /**
     * 字段归属收敛到服务端：M 目录没有组件/权限标识，F 按钮没有图标/路由/组件。
     *
     * <p>表单用 dependencies 隐藏这些字段，但 dependencies 只管渲染、不清值 ——
     * 编辑时把类型从 M 改成 F，原先的图标和路由仍会随请求体提交。这里按类型
     * 归一化一次，保证无论调用方传什么，库里都不会出现「按钮带着组件路径」。
     */
    private void copy(MenuBody body, SysMenu menu) {
        menu.setParentId(body.getParentId());
        menu.setMenuName(body.getMenuName());
        menu.setMenuType(body.getMenuType());
        boolean isDir = "M".equals(body.getMenuType());
        boolean isMenu = "C".equals(body.getMenuType());
        boolean isButton = "F".equals(body.getMenuType());
        menu.setIcon(isButton ? null : body.getIcon());
        menu.setRoutePath(isButton ? null : body.getRoutePath());
        menu.setComponent(isMenu ? body.getComponent() : null);
        menu.setPerms(isDir ? null : body.getPerms());
        menu.setSort(body.getSort() == null ? 0 : body.getSort());
        menu.setVisible(body.getVisible() == null ? 1 : body.getVisible());
        menu.setStatus(body.getStatus() == null ? 1 : body.getStatus());
    }

    private void validateParent(Long parentId) {
        if (parentId != 0 && menuMapper.selectById(parentId) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "父菜单不存在");
        }
    }

    private SysMenu requireMenu(Long id) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        return menu;
    }
}
