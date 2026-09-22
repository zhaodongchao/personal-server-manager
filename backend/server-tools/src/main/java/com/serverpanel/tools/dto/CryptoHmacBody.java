package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * HMAC 请求体。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class CryptoHmacBody {

    /** 待处理文本 */
    @NotBlank(message = "待处理文本不能为空")
    private String text;

    /** 密钥 */
    @NotBlank(message = "密钥不能为空")
    private String key;

    /** 密钥录入方式：TEXT / HEX / BASE64，默认 TEXT */
    private String keyEncoding = "TEXT";

    /** 算法：MD5 / SHA1 / SHA256 / SHA512 */
    @NotBlank(message = "算法不能为空")
    private String algorithm;

    /** 输出是否大写 */
    private boolean upper = false;
}
