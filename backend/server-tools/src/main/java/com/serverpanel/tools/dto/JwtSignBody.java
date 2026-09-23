package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * JWT 签发（生成签名）请求体。
 *
 * <p>Header 由服务端拼装：{@code alg} 由 {@link #algorithm} 决定，用户只能追加
 * 额外字段（如 {@code kid}、{@code typ}），<b>不允许覆盖 alg</b>。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class JwtSignBody {

    /** 签名算法（白名单，不含 none） */
    @NotBlank(message = "签名算法不能为空")
    private String algorithm;

    /** Payload（JSON 对象原文，按用户输入原样编码，保持声明顺序） */
    @NotBlank(message = "Payload 不能为空")
    private String payload;

    /** Header 附加字段（JSON 对象，可空。alg 不可覆盖） */
    private String header;

    /** 密钥录入方式：SECRET / PEM / JWK，默认 SECRET */
    private String keyFormat = "SECRET";

    /** 签名密钥：对称密钥文本、PEM 私钥、或 JWK/JWKS JSON */
    @NotBlank(message = "密钥不能为空")
    private String key;

    /** 对称密钥的文字编码：TEXT / BASE64 / HEX，默认 TEXT */
    private String secretEncoding = "TEXT";
}
