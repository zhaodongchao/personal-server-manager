package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对称加解密请求体。
 *
 * <p>方向语义（务必与前端一致）：
 * <ul>
 *   <li>{@code ENCRYPT}：{@code text} 是明文，输出按 {@code outputEncoding} 编码；</li>
 *   <li>{@code DECRYPT}：{@code text} 是按 {@code inputEncoding} 编码的密文，输出明文。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class CryptoSymmetricBody {

    /** 待处理文本 */
    @NotBlank(message = "待处理文本不能为空")
    private String text;

    /** 算法：AES / DES / 3DES / RC4 */
    @NotBlank(message = "算法不能为空")
    private String algorithm;

    /** 密钥 */
    @NotBlank(message = "密钥不能为空")
    private String key;

    /** 密钥录入方式：TEXT / HEX / BASE64，默认 TEXT */
    private String keyEncoding = "TEXT";

    /** 初始向量（CBC 必填，按 ivEncoding 解析） */
    private String iv;

    /** IV 录入方式：TEXT / HEX / BASE64，默认 TEXT */
    private String ivEncoding = "TEXT";

    /** 分组模式：ECB / CBC，默认 CBC；RC4 忽略 */
    private String mode = "CBC";

    /** 填充：PKCS5Padding / NoPadding，默认 PKCS5Padding；RC4 忽略 */
    private String padding = "PKCS5Padding";

    /** ENCRYPT / DECRYPT，默认 ENCRYPT */
    private String op = "ENCRYPT";

    /** 解密时密文的编码：BASE64 / HEX，默认 BASE64 */
    private String inputEncoding = "BASE64";

    /** 加密时密文的输出编码：BASE64 / HEX，默认 BASE64 */
    private String outputEncoding = "BASE64";
}
