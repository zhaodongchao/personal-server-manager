package com.serverpanel.tools.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 行政区划 sys_region（4 级：省/市/区县/乡镇街道）。
 *
 * <p>数据量大（约 4.5 万行），不进 Flyway 种子；由「基础数据」页手动或
 * 定时任务从国家统计局口径数据源同步。年度更新中被撤销的代码置 status=0
 * 软删保留，老身份证的历史区划仍可解析。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_region")
public class SysRegion extends BaseEntity {

    /**
     * 主键序列化为字符串：雪花 ID 超出 JS 安全整数范围（与其它模块口径一致）。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 区划代码：省2/市4/县6/乡9位（统计局口径） */
    private String code;

    /** 名称（全称） */
    private String name;

    /** 简称（如：内蒙古） */
    private String shortName;

    /** 1省 2市 3区县 4乡镇街道（预留5村居） */
    private Integer level;

    /** 父级代码（省级为空串） */
    private String parentCode;

    /** 1启用 0停用（被撤销的置0，保留可解析性） */
    private Integer status;
}
