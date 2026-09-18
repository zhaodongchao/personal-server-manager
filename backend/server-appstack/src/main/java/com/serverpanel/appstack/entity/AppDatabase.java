package com.serverpanel.appstack.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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

    /** 库名（同时为授权账号名） */
    private String dbName;

    /** 授权账号 */
    private String dbUser;

    /** 字符集，默认 utf8mb4 */
    private String charset;

    private String remark;
}
