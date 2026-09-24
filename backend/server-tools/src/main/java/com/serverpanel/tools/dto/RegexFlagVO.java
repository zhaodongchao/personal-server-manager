package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正则标志（flags）选项。
 *
 * <p>只暴露 Java {@link java.util.regex.Pattern} 与常见前端语义都支持或可解释的
 * 五个标志，避免「前端能填、后端报错」的落差。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexFlagVO {

    /** 标志字符：i / m / s / x / u */
    private String value;

    /** 界面展示名 */
    private String label;

    /** 说明 */
    private String desc;
}
