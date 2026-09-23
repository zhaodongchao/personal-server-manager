package com.serverpanel.tools.dto;

import lombok.Data;

/**
 * JWT 签发结果。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class JwtSignResultVO {

    /** 生成的 JWT */
    private String token;

    /** 服务端最终写入的 Header（紧凑 JSON 原文） */
    private String header;

    /** 实际使用的算法 */
    private String algorithm;
}
