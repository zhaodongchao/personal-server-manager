package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正则结构解析出的一个片段（token）。
 *
 * <p>解析器是教学级的手写扫描器：不追求 AST 级精确，目标是把模式切成
 * 「人能读懂的分段」——字面量 / 字符类 / 量词 / 分组 / 锚点 / 或 / 点号，
 * 每段配一句中文说明；嵌套分组用 depth 供前端缩进展示。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexTokenVO {

    /** 原文片段（从模式里原样截取） */
    private String token;

    /** 片段类型：literal / charClass / quantifier / group / groupEnd / anchor / alternation / dot */
    private String type;

    /** 嵌套深度（分组内 +1，前端按此缩进） */
    private int depth;

    /** 中文说明 */
    private String desc;
}
