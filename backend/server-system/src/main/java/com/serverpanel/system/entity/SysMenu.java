package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单权限表 sys_menu。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    /** 父菜单 ID，0 为根 */
    private Long parentId;

    private String menuName;

    /** M 目录 / C 菜单 / F 按钮 */
    private String menuType;

    /** 路由地址 */
    private String routePath;

    /** 前端组件路径（Vben 后端路由格式） */
    private String component;

    /** 权限标识，如 system:user:add */
    private String perms;

    private String icon;

    private Integer sort;

    /** 1 显示 0 隐藏 */
    private Integer visible;

    /** 1 启用 0 停用 */
    private Integer status;
}
