package com.serverpanel.tools.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 银行卡 BIN sys_bank_bin（证件解析的发卡行数据源，可在管理页维护）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_bank_bin")
public class SysBankBin extends BaseEntity {

    /**
     * 主键序列化为字符串：雪花 ID 超出 JS 安全整数范围（与其它模块口径一致）。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** BIN 前缀（6-10位） */
    private String bin;

    /** 发卡行全称（中国工商银行） */
    private String bankName;

    /** 简称（工行） */
    private String bankShort;

    /** 1借记卡 2贷记卡(信用卡) 3准贷记卡 */
    private Integer cardType;

    /** 标准卡长（16/19） */
    private Integer cardLen;

    /** 备注 */
    private String note;
}
