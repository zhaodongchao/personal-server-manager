package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 编码/解码请求体（Base64、Hex）。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class CryptoCodecBody {

    /** 待处理文本 */
    @NotBlank(message = "待处理文本不能为空")
    private String text;

    /** 编码方式：BASE64 / HEX */
    @NotBlank(message = "编码方式不能为空")
    private String algorithm;

    /** ENCODE / DECODE，默认 ENCODE */
    private String op = "ENCODE";
}
