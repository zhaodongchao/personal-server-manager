package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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

    /** 头像：NULL/空=默认 /avatar.svg；preset:N=内置预设；data:image/...;base64,...=自定义上传 */
    private String avatar;

    /** 个人简介（desc 为 MySQL 保留字，须加反引号） */
    @TableField("`desc`")
    private String desc;

    /** 1 启用 0 停用 */
    private Integer status;

    /** 性别 0未知 1男 2女 */
    private Integer gender;

    /** 头像最后更新时间（前端缓存失效判断用） */
    private LocalDateTime avatarUpdatedAt;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;
}
