package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对称算法的可选形态。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CipherOptionVO {

    /** 算法取值：AES / DES / 3DES / RC4 */
    private String value;

    /** 展示名 */
    private String label;

    /** 允许的密钥字节长度；空集合表示任意长度（如 RC4） */
    private List<Integer> keyLengths;

    /** 支持的分组模式；空集合表示流算法（RC4）无分组模式 */
    private List<String> modes;

    /** CBC 模式所需 IV 字节长度；0 表示不需要 IV */
    private int ivLength;

    /** 安全提示 */
    private String note;
}
