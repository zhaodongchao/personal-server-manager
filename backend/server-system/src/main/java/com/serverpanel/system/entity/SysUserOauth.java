package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户第三方登录绑定表 sys_user_oauth。
 *
 * <p>账号策略：先绑定后登录。记录由已登录用户在个人中心主动创建，
 * 登录页第三方登录仅按 (provider, openId) 查本表命中后放行，不做自动注册。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_oauth")
public class SysUserOauth extends BaseEntity {

    /** 面板用户 ID（sys_user.id） */
    private Long userId;

    /** OAuth 平台：GITEE / GITHUB / DINGTALK / WECHAT_OPEN / QQ */
    private String provider;

    /** 平台用户唯一标识（JustAuth AuthUser.uuid） */
    private String openId;

    /** 平台 unionid（微信等跨应用标识，可空） */
    private String unionId;

    /** 第三方昵称（绑定时刻快照） */
    private String nickname;

    /** 第三方头像 URL（绑定时刻快照） */
    private String avatar;
}
