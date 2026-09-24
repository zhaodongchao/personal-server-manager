package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 正则生成结果：拼好的模式 + 逐段中文说明 + 可匹配示例 + 注意事项。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexGenerateResultVO {

    /** 生成的正则表达式 */
    private String pattern;

    /** 应用的标志（原样规范化后的 imux 串） */
    private String flags;

    /** 逐段中文说明（每行对应模式的一个组成段） */
    private List<String> explanation;

    /** 能匹配该模式的示例文本（可直接「填入测试」验证） */
    private List<String> samples;

    /** 注意事项（如身份证不含校验位算法、密码不含长度上限） */
    private List<String> notes;
}
