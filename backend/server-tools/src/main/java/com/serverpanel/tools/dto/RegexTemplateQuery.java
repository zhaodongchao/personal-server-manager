package com.serverpanel.tools.dto;

import com.serverpanel.common.core.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 正则模板分页查询参数。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RegexTemplateQuery extends PageQuery {

    /** 关键字：模糊匹配名称与用途说明 */
    private String keyword;

    /** 分类精确筛选 */
    private String category;
}
