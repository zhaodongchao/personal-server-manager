package com.serverpanel.tools.cert.dto;

/**
 * 构造规则条目（options 下发，前端折叠面板渲染）。
 *
 * @param label 字段名（如「地址码」）
 * @param desc  规则说明
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record RuleItemVO(String label, String desc) {
}
