package com.serverpanel.appstack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 取号对象初始化结果。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@AllArgsConstructor
public class IdSourceInitVO {

    /** 本次实际做了什么（幂等：重复调用会说明「已存在，无需创建」） */
    private String message;

    /** 取号对象名（序列名或自增表名） */
    private String target;

    /** 数据库版本，顺带证明连接确实通了 */
    private String serverVersion;
}
