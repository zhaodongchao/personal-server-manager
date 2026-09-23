package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.serverpanel.system.jackson.FlexIntegerDeserializer;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 快捷导航配置 sys_quick_nav（工作台快捷导航数据源）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_quick_nav")
public class SysQuickNav extends BaseEntity {

    /**
     * 主键序列化为字符串：新增记录走雪花 ID（19 位），超出 JS 安全整数范围，
     * 直接吐 Number 会被浏览器四舍五入成另一个 id，导致编辑/删除打到错行。
     * 与其它模块的雪花主键实体口径一致；基类字段只能在 getter 上覆写。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 展示名 */
    private String displayName;

    /** Web 端口（-1 未知） */
    private Integer port;

    /** Web 路径前缀 */
    private String path;

    /** 前端图标（如 lucide:gitlab） */
    private String icon;

    /** 排序（小在前） */
    private Integer sort;

    /** 1 启用 0 停用 */
    @JsonDeserialize(using = FlexIntegerDeserializer.class)
    private Integer status;

    private String remark;
}
