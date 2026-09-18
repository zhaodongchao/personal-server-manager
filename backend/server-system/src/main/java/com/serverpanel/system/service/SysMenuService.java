package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    private void copy(MenuBody body, SysMenu menu) {
        menu.setParentId(body.getParentId());
        menu.setMenuName(body.getMenuName());
        menu.setMenuType(body.getMenuType());
        menu.setRoutePath(body.getRoutePath());
        menu.setComponent(body.getComponent());
        menu.setPerms(body.getPerms());
        menu.setIcon(body.getIcon());
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
