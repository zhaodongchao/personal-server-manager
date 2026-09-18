package com.serverpanel.common.constant;

/**
 * 通用常量。
 */
public final class CommonConstants {

    private CommonConstants() {}

    /** 超级管理员角色标识（拥有全部权限，不走菜单授权匹配） */
    public static final String SUPER_ADMIN_ROLE_KEY = "admin";

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;

    /** 状态：停用 */
    public static final int STATUS_DISABLED = 0;

    /** 菜单类型：目录 */
    public static final String MENU_TYPE_DIR = "M";

    /** 菜单类型：菜单 */
    public static final String MENU_TYPE_MENU = "C";

    /** 菜单类型：按钮 */
    public static final String MENU_TYPE_BUTTON = "F";

    /** 根菜单 parent_id */
    public static final Long ROOT_PARENT_ID = 0L;

    /** 内置数据标识（sys_config.config_type = Y） */
    public static final String CONFIG_TYPE_BUILTIN = "Y";
}
