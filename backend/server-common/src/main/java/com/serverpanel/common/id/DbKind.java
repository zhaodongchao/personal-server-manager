package com.serverpanel.common.id;

import java.util.Locale;

/**
 * 取号数据源的数据库类型。
 *
 * <p>只列本面板真正支持的两种。每种类型对应一套「取号原语」：
 * PostgreSQL 走序列（{@code nextval}），MySQL 走自增列（{@code INSERT} +
 * {@code LAST_INSERT_ID()}）—— 二者都是各自数据库里最原生的取号方式。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public enum DbKind {

    /** PostgreSQL（含兼容协议的衍生库） */
    POSTGRESQL("PostgreSQL"),

    /** MySQL / MariaDB */
    MYSQL("MySQL");

    private final String label;

    DbKind(String label) {
        this.label = label;
    }

    /** 展示名 */
    public String label() {
        return label;
    }

    /**
     * 宽松解析：大小写不敏感，并接受常见别名。
     *
     * @param value 原始文本（可空）
     * @return 识别到的类型；无法识别返回 {@code null}（由调用方决定报错还是忽略）
     */
    public static DbKind of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "POSTGRESQL", "POSTGRES", "PG", "PGSQL" -> POSTGRESQL;
            case "MYSQL", "MARIADB" -> MYSQL;
            default -> null;
        };
    }
}
