package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正则工具各项处理上限（前端用于输入框 maxlength 与提示文案）。
 *
 * <p>Java 无法对正则匹配设置超时，灾难性回溯的缓解手段就是这些上限：
 * 模式与文本限长 + 匹配条数封顶。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexLimitsVO {

    /** 正则表达式最大长度（测试用） */
    private int maxPatternLength;

    /** 待匹配文本最大长度 */
    private int maxTestTextLength;

    /** 单次返回的匹配条数上限（超出置 truncated） */
    private int maxMatches;

    /** 模板全量列表的最大条数 */
    private int maxTemplateCount;
}
