package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * JWT 验签请求体。
 *
 * <p>解码（Header/Payload 解析）与时间声明校验由前端完成，本接口只负责
 * 「用给定密钥验证签名」这一件需要密钥的事。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class JwtVerifyBody {

    /** 待验证的 JWT */
    @NotBlank(message = "JWT 不能为空")
    private String token;

    /** 密钥录入方式：SECRET / PEM / JWK，默认 SECRET */
    private String keyFormat = "SECRET";

    /** 密钥内容：对称密钥文本、PEM 公钥/私钥、或 JWK/JWKS JSON */
    @NotBlank(message = "密钥不能为空")
    private String key;

    /** 对称密钥的文字编码：TEXT / BASE64 / HEX，默认 TEXT */
    private String secretEncoding = "TEXT";
}
