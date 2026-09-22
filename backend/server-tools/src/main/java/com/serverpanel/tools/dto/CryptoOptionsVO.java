package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * 字符串加解密页的可选清单。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class CryptoOptionsVO {

    /** 摘要算法 */
    private List<OptionVO> digests;

    /** HMAC 算法 */
    private List<OptionVO> hmacs;

    /** 编码方式（Base64 / Hex） */
    private List<OptionVO> encodings;

    /** 对称算法及其形态约束 */
    private List<CipherOptionVO> ciphers;

    /** 密钥录入方式：TEXT / HEX / BASE64 */
    private List<OptionVO> keyEncodings;

    /** 密文输出（或解密输入）编码：BASE64 / HEX */
    private List<OptionVO> cipherEncodings;

    /** 单次处理文本上限（字符数） */
    private int maxChars;
}
