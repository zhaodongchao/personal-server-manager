package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * 混淆页的可选清单。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class ObfuscateOptionsVO {

    /** 混淆方式 */
    private List<ObfuscateMethodVO> methods;

    /** 单次处理文本上限（字符数） */
    private int maxChars;
}
