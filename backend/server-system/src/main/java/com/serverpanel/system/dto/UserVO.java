package com.serverpanel.system.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户列表视图对象。
 *
 * <p><b>刻意不包含 {@code avatar} 字段</b>：头像是 MEDIUMTEXT（最大 1MB 的 base64），
 * 一旦随列表返回会让响应体积随用户数线性膨胀。列表页也不需要真实头像，
 * 头像仅在「编辑弹窗」（{@link UserDetailVO}）与个人中心按单条读取。
 *
 * @author zhaodc
 * @since 2026-09-21
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    /** 性别 0未知 1男 2女 */
    private Integer gender;

    private String email;

    private String phone;

    private Integer status;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
