package com.serverpanel.tools.dto;

import lombok.Data;

/**
 * JWT 验签结果。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class JwtVerifyResultVO {

    /** 签名是否验证通过（{@code alg=none} 恒为 false） */
    private boolean verified;

    /** Header 中声明的算法 */
    private String algorithm;

    /** 算法族：HMAC / RSA / RSA-PSS / ECDSA / EdDSA / none / unknown */
    private String family;

    /** 实际使用的密钥类型：HMAC / RSA / EC / OKP，未取到密钥时为 null */
    private String keyType;

    /** JWK/JWKS 场景下命中的密钥 id（可空） */
    private String kid;

    /** 结论说明（中文），通过时为「签名验证通过」 */
    private String reason;
}
