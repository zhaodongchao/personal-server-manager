package com.serverpanel.appstack.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 取号数据源（app_id_source）—— ID 生成器里「自增 / 序列」两类方案的真连库目标。
 *
 * <p><b>口令存密文</b>：{@code password_cipher} 由 {@code SecretCipher} 加密后落库，
 * 实体层永远不出现明文字段，避免被日志或序列化顺手带出去。
 *
 * <p><b>为什么这张表在应用栈而不是日常工具</b>：它管理的是「数据库连接」这一
 * 应用栈领域的资源，与「面板代管 MySQL 库」（{@code app_database}）同类，
 * 因此归口应用栈；ID 生成器通过 SPI 消费它，而不是自己持有连接信息。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_id_source")
public class AppIdSource extends BaseEntity {

    /** 数据源名称（展示用，唯一性由业务侧把关） */
    private String name;

    /** 库类型：POSTGRESQL / MYSQL */
    private String dbType;

    private String host;

    private Integer port;

    /** 库名（受库黑名单约束，不得为面板库或其它业务生产库） */
    private String dbName;

    private String username;

    /** 口令密文（AES-256-GCM，含 v1: 前缀） */
    private String passwordCipher;

    /** 自增表名（MYSQL_AUTO_INCREMENT 用；空则回落到默认 psm_id_demo） */
    private String tableName;

    /** 序列名（SEQUENCE 用；空则回落到默认 psm_id_seq） */
    private String sequenceName;

    /** 首次取号前是否自动初始化取号对象：1 是 / 0 否 */
    private Integer autoInit;

    /** 状态：1 启用 / 0 停用（停用后不再出现在下拉里） */
    private Integer status;

    private String remark;
}
