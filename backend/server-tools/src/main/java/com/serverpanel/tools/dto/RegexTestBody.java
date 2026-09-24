package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 正则测试请求：匹配 + 结构解析 + 可选替换预览。
 *
 * <p>长度上限是灾难性回溯的核心缓解手段（Java 无法对匹配设超时）。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class RegexTestBody {

    /** 正则表达式 */
    @NotBlank(message = "正则表达式不能为空")
    @Size(max = 1000, message = "正则表达式不能超过 1000 字符")
    private String pattern;

    /** 标志组合（imux 子集），空串表示无标志 */
    @Size(max = 10, message = "正则标志过长")
    private String flags;

    /** 待匹配文本 */
    @NotBlank(message = "待匹配文本不能为空")
    @Size(max = 100000, message = "待匹配文本不能超过 100000 字符")
    private String text;

    /** 替换串（非 null 时返回替换预览，支持 $1 / ${name} 组引用） */
    private String replacement;
}
