package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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
    private Integer status;

    private String remark;
}
