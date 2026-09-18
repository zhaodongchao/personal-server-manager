package com.serverpanel.system.dto.auth;

import lombok.Data;

import java.util.List;

/**
 * Vben Admin 后端路由节点（accessMode: backend）。
 */
@Data
public class RouteVO {

    /** 'Layout'（目录容器）或页面组件路径 '/system/user/index' */
    private String component;

    private String path;

    /** 路由名（须唯一，由 path 驼峰化生成） */
    private String name;

    /** 目录重定向到首个子菜单 */
    private String redirect;

    private Meta meta;

    private List<RouteVO> children;

    /**
     * Vben 路由 meta。
     */
    @lombok.Data
    public static class Meta {
        private String title;
        private String icon;
        private Integer order;
        private Boolean hideInMenu;

        public Meta(String title, String icon, Integer order, Boolean hideInMenu) {
            this.title = title;
            this.icon = icon;
            this.order = order;
            this.hideInMenu = hideInMenu;
        }
    }
}
