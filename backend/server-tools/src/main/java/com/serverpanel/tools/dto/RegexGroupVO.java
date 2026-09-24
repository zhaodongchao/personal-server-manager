package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正则捕获组取值。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexGroupVO {

    /** 组号（0 表示整体匹配，1 起为捕获组） */
    private int index;

    /** 命名组的名称（非命名组为 null） */
    private String name;

    /** 组匹配到的文本（该组未参与匹配时为 null） */
    private String value;
}
