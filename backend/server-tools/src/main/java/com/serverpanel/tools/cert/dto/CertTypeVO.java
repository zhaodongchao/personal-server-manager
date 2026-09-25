package com.serverpanel.tools.cert.dto;

import java.util.List;

/**
 * 单个证件类型的清单项（GET /tools/cert/options 的 types 元素）。
 *
 * @param key         类型标识（请求解析时回传）
 * @param name        显示名
 * @param icon        图标（iconify）
 * @param needRegion  是否依赖行政区划数据（用于前端提示降级状态）
 * @param placeholder 输入框提示
 * @param samples     示例（点击回填）
 * @param rules       构造规则（折叠面板渲染）
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record CertTypeVO(
        String key,
        String name,
        String icon,
        boolean needRegion,
        String placeholder,
        List<String> samples,
        List<RuleSectionVO> rules) {
}
