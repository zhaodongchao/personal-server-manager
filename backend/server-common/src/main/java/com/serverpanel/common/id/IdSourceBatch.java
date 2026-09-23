package com.serverpanel.common.id;

import java.util.List;

/**
 * 一次真连库取号的原始结果。
 *
 * <p>刻意用「平行列表」而不是结构化对象：各数据库的取号原语差异很大
 * （PG 的 {@code nextval} 每次只回一个值、MySQL 的 {@code LAST_INSERT_ID()} 每行一个值，
 * 但两者都可能带「会话/连接」这类附加说明），平行列表能容纳差异而不必在 common 里
 * 定义一堆近乎重复的类型。转换成本模块内的 DTO 由调用方负责。
 *
 * @param values   取到的号（按取号顺序，字符串形式 —— 大整数必须字符串承载）
 * @param extras   与 {@code values} 一一对应的附加说明（可为空列表）
 * @param notes    原理性说明（随结果一起展示在页面上）
 * @param warnings 风险提示（例如空洞、跳号、预分配浪费）
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public record IdSourceBatch(
        List<String> values,
        List<String> extras,
        List<String> notes,
        List<String> warnings) {

    public static IdSourceBatch of(List<String> values, List<String> extras,
                                   List<String> notes, List<String> warnings) {
        return new IdSourceBatch(List.copyOf(values), List.copyOf(extras),
                List.copyOf(notes), List.copyOf(warnings));
    }
}
