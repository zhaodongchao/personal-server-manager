package com.serverpanel.appstack.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 面板代管 MySQL 库（app_database）。
 *
 * <p>只记录库名、授权账号与字符集；账号密码不入库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_database")
public class AppDatabase extends BaseEntity {

    /**
     * 主键序列化为字符串：新增登记走雪花 ID（19 位），超出 JS 安全整数范围，
     * 直接吐 Number 会被精度截断，备份/恢复/删除会打到错库。
     * 基类字段只能在 getter 上覆写。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 库名（同时为授权账号名） */
    private String dbName;

    /** 授权账号 */
    private String dbUser;

    /** 字符集，默认 utf8mb4 */
    private String charset;

    private String remark;
}
