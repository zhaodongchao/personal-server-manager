package com.serverpanel.tools.cert.dto;

/**
 * 解析结果字段行。
 *
 * @param label 字段名（如「出生日期」）
 * @param value 字段值（已格式化的展示文本）
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record CertFieldVO(String label, String value) {
}
