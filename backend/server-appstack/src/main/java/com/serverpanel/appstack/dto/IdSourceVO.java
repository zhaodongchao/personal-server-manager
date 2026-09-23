package com.serverpanel.appstack.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 取号数据源出参。
 *
 * <p>{@code id} 为字符串：19 位雪花 ID 超出 JS 安全整数范围，直接给 number 会精度丢失，
 * 回传时查不到记录（本项目实测过的高频缺陷）。
 *
 * <p>{@code passwordMasked} 固定为 {@code ******}，绝不回显明文或密文 ——
 * 与执行器令牌（{@code ExecutorVO.authTokenMasked}）保持同一口径。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdSourceVO {

    private String id;

    private String name;

    /** POSTGRESQL / MYSQL */
    private String dbType;

    /** 展示名：PostgreSQL / MySQL */
    private String dbTypeLabel;

    private String host;

    private Integer port;

    private String dbName;

    private String username;

    /** 口令掩码，固定 ****** */
    private String passwordMasked;

    /** 取号对象：自增表名或序列名（已回落到默认值） */
    private String tableName;

    private String sequenceName;

    private boolean autoInit;

    private boolean status;

    private String remark;

    /**
     * 口令加密密钥是否可用。
     *
     * <p>不可用时列表照常展示（历史记录仍可读），但保存会被拒绝 ——
     * 把这一位下发给前端，页面就能提前给出「请配置 serverpanel.secret.key」
     * 的提示，而不是等用户填完整张表单再报错。
     */
    private boolean cipherReady;

    private LocalDateTime createdAt;
}
