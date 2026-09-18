package com.serverpanel.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点（管理页）。
 */
@Data
public class MenuVO {

    private Long id;

    private Long parentId;

    private String menuName;

    private String menuType;

    private String routePath;

    private String component;

    private String perms;

    private String icon;

    private Integer sort;

    private Integer visible;

    private Integer status;

    private List<MenuVO> children;
}
