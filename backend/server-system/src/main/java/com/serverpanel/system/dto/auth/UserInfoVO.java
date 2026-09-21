package com.serverpanel.system.dto.auth;

import lombok.Data;

import java.util.List;

/**
 * 当前用户信息（Vben Admin 约定字段）。
 */
@Data
public class UserInfoVO {

    private String userId;

    private String username;

    private String realName;

    private String desc;

    /** 归一化后的可渲染头像 src（预设映射为站内静态资源，自定义为 data URL） */
    private String avatar;

    /** 头像入库原始值：null / preset:N / data:image/...;base64,...（个人中心回显用） */
    private String avatarRaw;

    /** 性别 0未知 1男 2女 */
    private Integer gender;

    private String email;

    private String phone;

    private String homePath;

    /** 角色 key 列表 */
    private List<String> roles;
}
