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

    private String avatar;

    private String homePath;

    /** 角色 key 列表 */
    private List<String> roles;
}
