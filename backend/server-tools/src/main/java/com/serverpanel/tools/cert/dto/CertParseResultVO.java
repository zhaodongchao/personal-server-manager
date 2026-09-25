package com.serverpanel.tools.cert.dto;

import java.util.List;
import java.util.Map;

/**
 * 证件解析结果（统一结构 + 类型扩展字段）。
 *
 * <p>解析「失败」不等于接口错误：结构/校验不合法返回 {@code valid=false}，
 * HTTP 仍是 200，便于前端按结论着色而不是按异常弹窗。
 *
 * @param type   证件类型
 * @param valid  是否通过全部校验
 * @param level  结论级别：ok / warn / error
 * @param errors 结构或校验错误明细
 * @param warns  合法但需要注意的事项（15 位老证、未收录号段、区划未同步等）
 * @param fields 解析出的字段表格
 * @param extra  类型扩展信息（键为类型 key，值为各类型自有结构）
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record CertParseResultVO(
        String type,
        boolean valid,
        String level,
        List<String> errors,
        List<String> warns,
        List<CertFieldVO> fields,
        Map<String, Object> extra) {

    /** 构造空骨架（默认 ok 级别） */
    public static CertParseResultVO empty(String type) {
        return new CertParseResultVO(type, true, "ok", List.of(), List.of(), List.of(), Map.of());
    }
}
