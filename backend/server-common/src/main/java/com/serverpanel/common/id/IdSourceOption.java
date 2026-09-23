package com.serverpanel.common.id;

/**
 * 取号数据源的下拉选项（前端「数据源」下拉用）。
 *
 * <p>{@code value} 用字符串而非数字，是为了直接对齐前端表单的值类型 ——
 * 面板所有工具页的表单参数都是字符串键值，混入数字会在透传时反复转换。
 *
 * @param value  选项值（数据源 ID 的十进制字符串）
 * @param label  选项文案（名称 + 库类型 + 库名）
 * @param dbType 库类型编码，供前端判断该源支持哪类方案
 * @param target 取号对象名（序列名或自增表名），供前端提示
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public record IdSourceOption(String value, String label, String dbType, String target) {
}
