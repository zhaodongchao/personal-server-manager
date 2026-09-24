package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 正则模板出参。
 *
 * <p>id 以字符串下发（雪花 ID 超出 JS 安全整数范围），与实体侧的
 * ToStringSerializer 口径一致。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexTemplateVO {

    /** 模板 ID（雪花 ID 字符串化） */
    private String id;

    /** 模板名称 */
    private String name;

    /** 正则表达式 */
    private String pattern;

    /** 标志组合（imux 子集） */
    private String flags;

    /** 分类 */
    private String category;

    /** 用途说明 */
    private String description;

    /** 示例文本提示 */
    private String sample;

    /** 排序 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
