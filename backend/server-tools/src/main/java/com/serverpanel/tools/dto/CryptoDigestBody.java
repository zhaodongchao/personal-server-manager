package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 摘要请求体。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class CryptoDigestBody {

    /** 待处理文本 */
    @NotBlank(message = "待处理文本不能为空")
    private String text;

    /** 算法：MD5 / SHA1 / SHA256 / SHA512 */
    @NotBlank(message = "算法不能为空")
    private String algorithm;

    /** 输出是否大写，默认 false（小写） */
    private boolean upper = false;
}
