package com.serverpanel.tools.cert.dto;

import java.util.List;

/**
 * 证件解析可选清单（GET /api/v1/tools/cert/options）。
 *
 * @param types  证件类型清单（含构造规则）
 * @param limits 各项上限与数据就绪状态
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record CertOptionsVO(List<CertTypeVO> types, Limits limits) {

    /**
     * 上限与数据状态。
     *
     * @param maxValueLength 证件号最大长度
     * @param regionReady    行政区划是否已同步（false 时身份证/信用代码区划降级）
     */
    public record Limits(int maxValueLength, boolean regionReady) {
    }
}
