package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * JWT 工具可选清单。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class JwtOptionsVO {

    /** 支持的签名算法（白名单，服务端为唯一真源） */
    private List<JwtAlgorithmVO> algorithms;

    /** 密钥录入方式：SECRET（对称密钥）/ PEM（公钥或私钥）/ JWK（JSON） */
    private List<OptionVO> keyFormats;

    /** 对称密钥的文字编码：TEXT / BASE64 / HEX */
    private List<OptionVO> secretEncodings;

    /** 单次处理 token 上限（字符数） */
    private int maxChars;
}
