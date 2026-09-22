package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 混淆请求体。
 *
 * <p>各方式的参数差异较大（XOR 要 key、凯撒要 shift、嵌套要 times……），
 * 故用 {@code params} 承载，字段定义由 {@code GET /options} 下发，
 * 与内置任务的 {@code execute(Map)} 保持同一风格。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class ObfuscateBody {

    /** 待处理文本 */
    @NotBlank(message = "待处理文本不能为空")
    private String text;

    /** 混淆方式：XOR / CAESAR / REVERSE / BASE64_NESTED / ZERO_WIDTH */
    @NotBlank(message = "混淆方式不能为空")
    private String method;

    /** OBFUSCATE / DEOBFUSCATE，默认 OBFUSCATE */
    private String op = "OBFUSCATE";

    /** 按方式而定的参数 */
    private Map<String, String> params;
}
