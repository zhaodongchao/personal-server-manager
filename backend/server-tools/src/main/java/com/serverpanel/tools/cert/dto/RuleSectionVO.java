package com.serverpanel.tools.cert.dto;

import java.util.List;

/**
 * 构造规则分组（一个证件若干分组，一组若干条目）。
 *
 * @param title 分组标题（如「结构」「校验算法」）
 * @param items 条目列表
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record RuleSectionVO(String title, List<RuleItemVO> items) {
}
