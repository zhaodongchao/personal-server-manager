package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次正则匹配的明细。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexMatchVO {

    /** 匹配起点（0 基，含） */
    private int index;

    /** 匹配终点（0 基，不含） */
    private int end;

    /** 匹配到的文本 */
    private String text;

    /** 捕获组明细（第 0 项为整体匹配） */
    private List<RegexGroupVO> groups;
}
