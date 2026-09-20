package com.serverpanel.framework.security;

import java.util.Optional;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 当前登录用户工具（Sa-Token 封装）。
 *
 * <p>登录成功时向 Sa-Token Session 写入 username / nickname，
 * 此处提供统一读取入口；未登录时抛出 NotLoginException 由全局处理器转 401。
 */
public final class LoginHelper {

    /** Session key */
    public static final String KEY_USERNAME = "username";

    public static final String KEY_NICKNAME = "nickname";

    private LoginHelper() {}

    /** 当前用户 ID */
    public static long getUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /** 当前登录名 */
    public static String getUsername() {
        return (String) StpUtil.getSession().get(KEY_USERNAME);
    }

    /** 当前昵称（未设置时回退登录名） */
    public static String getNickname() {
        return Optional.ofNullable((String) StpUtil.getSession().get(KEY_NICKNAME))
                .orElse(getUsername());
    }

    /** 是否已登录 */
    public static boolean isLogin() {
        return StpUtil.isLogin();
    }
}
