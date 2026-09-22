package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 混淆方式及其参数定义。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObfuscateMethodVO {

    /** 取值 */
    private String value;

    /** 展示名 */
    private String label;

    /** 说明 */
    private String desc;

    /** 是否对称（正反用同一套逻辑，如 XOR/倒序） */
    private boolean symmetric;

    /** 参数字段定义 */
    private List<FieldVO> fields;
}
