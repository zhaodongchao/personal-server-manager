package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户表 sys_user。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    private String nickname;

    /** BCrypt 哈希，任何响应不回显 */
    private String password;

    private String email;

    private String phone;

    private String avatar;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;
}
