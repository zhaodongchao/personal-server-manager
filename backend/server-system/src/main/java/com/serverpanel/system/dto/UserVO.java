package com.serverpanel.system.dto;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

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

    /**
     * 主键序列化为字符串：用户走雪花 ID（19 位），超出 JS 安全整数范围，
     * 直接吐 Number 会被浏览器精度截断，编辑/重置口令/删除会打到错号用户。
     */
    @JsonSerialize(using = ToStringSerializer.class)
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
