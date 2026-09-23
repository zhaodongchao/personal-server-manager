package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JWT 算法描述。
 *
 * <p>算法清单由服务端下发，前端不硬编码 —— 与加解密页、混淆页、定时任务的
 * schema 驱动口径保持一致。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtAlgorithmVO {

    /** JOSE 算法名，如 HS256 / RS256 / ES512 / EdDSA */
    private String value;

    /** 界面展示名 */
    private String label;

    /** 算法族：HMAC / RSA / RSA-PSS / ECDSA / EdDSA */
    private String family;

    /** 所需密钥类型：HMAC / RSA / EC / OKP */
    private String keyType;

    /** 是否可用于签发（none 类不签发，故固定 true；保留字段便于后续扩展） */
    private boolean signable;

    /** 说明（密钥长度、曲线、填充等要求） */
    private String note;
}
