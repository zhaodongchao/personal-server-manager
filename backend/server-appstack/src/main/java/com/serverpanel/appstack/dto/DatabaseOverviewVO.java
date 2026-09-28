package com.serverpanel.appstack.dto;

import lombok.Data;

/**
 * MySQL 真实库概览条目（来自 information_schema，合并面板纳管标记）。
 *
 * <p>覆盖 MySQL 中是否存在该库：已纳管（面板代管登记）与未纳管（手动/外部工具建的
 * 已有库）在同一列表中打标记区分，未纳管的可经「纳管」登记进面板统一管理。
 */
@Data
public class DatabaseOverviewVO {

    /** 纳管记录的 ID（雪花 19 位，序列化为字符串防 JS 精度截断；未纳管为 null） */
    private String id;

    /** 库名 */
    private String dbName;

    /** 默认字符集（取自 information_schema） */
    private String charset;

    /** 是否已被面板纳管（存在于 app_database 登记表） */
    private boolean managed;

    /** 纳管库的授权账号（沿用「账号=库名」约定；未纳管为 null） */
    private String dbUser;

    /** 纳管库的备注（未纳管为 null） */
    private String remark;
}