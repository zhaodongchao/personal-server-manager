package com.serverpanel.tools.cert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证件解析请求体。
 *
 * @param type  证件类型（见 {@link com.serverpanel.tools.cert.CertType}）
 * @param value 证件号码原文
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record CertParseBody(
        @NotBlank(message = "证件类型不能为空") String type,
        @NotBlank(message = "证件号码不能为空")
        @Size(max = 64, message = "证件号码超出长度限制") String value) {
}
