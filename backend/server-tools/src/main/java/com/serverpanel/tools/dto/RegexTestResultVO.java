package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 正则测试结果：合法性 + 匹配明细 + 结构解析 + 替换预览。
 *
 * <p>正则语法错误不算接口错误（沿用二维码识别「识别不出不算错误」的口径），
 * 以 valid=false + 中文 errorMessage 返回，前端据此渲染错误提示。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexTestResultVO {

    /** 正则是否合法并可编译 */
    private boolean valid;

    /** 不合法时的中文原因（含出错位置） */
    private String errorMessage;

    /** 实际应用的标志（规范化后的 imux 串） */
    private String flagsApplied;

    /** 匹配总数（受 maxMatches 封顶前的真实计数封顶值） */
    private int matchCount;

    /** 是否因超过单次返回上限被截断 */
    private boolean truncated;

    /** 匹配明细 */
    private List<RegexMatchVO> matches;

    /** 结构解析分段（模式不合法时为空列表） */
    private List<RegexTokenVO> structure;

    /** 替换预览（请求未传 replacement 时为 null） */
    private String replacementPreview;
}
