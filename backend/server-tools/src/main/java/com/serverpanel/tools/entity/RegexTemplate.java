package com.serverpanel.tools.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 正则模板 sys_regex_template（正则工具「常用模板」数据源，全局共享）。
 *
 * <p>所有能打开正则工具的用户可见、可在测试页下拉联动回填；
 * 增删改由具备 tools:regex:template:* 权限的用户操作。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_regex_template")
public class RegexTemplate extends BaseEntity {

    /**
     * 主键序列化为字符串：雪花 ID（19 位）超出 JS 安全整数范围，
     * 直接吐 Number 会被浏览器四舍五入成另一个 id，导致编辑/删除打到错行。
     * 与其它模块的雪花主键实体口径一致；基类字段只能在 getter 上覆写。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 模板名称（全局唯一） */
    private String name;

    /** 正则表达式 */
    private String pattern;

    /** 标志组合（imux 的子集，对应 Java Pattern 标志） */
    private String flags;

    /** 分类（校验/提取/替换/日志/其他） */
    private String category;

    /** 用途说明 */
    private String description;

    /** 示例文本提示 */
    private String sample;

    /** 排序（小在前） */
    private Integer sort;
}
