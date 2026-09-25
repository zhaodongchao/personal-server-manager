package com.serverpanel.tools.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 手机号段 sys_phone_segment（证件解析的运营商归属数据源，可在管理页维护）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_phone_segment")
public class SysPhoneSegment extends BaseEntity {

    /**
     * 主键序列化为字符串：雪花 ID 超出 JS 安全整数范围（与其它模块口径一致）。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 号段前缀（3-4位） */
    private String prefix;

    /** 运营商：移动/联通/电信/广电/虚拟运营商 */
    private String operator;

    /** 1基础运营商 2虚拟运营商 3物联卡 */
    private Integer segType;

    /** 备注 */
    private String note;
}
