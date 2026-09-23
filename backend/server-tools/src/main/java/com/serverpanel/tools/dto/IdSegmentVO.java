package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ID 位段定义：描述一个 ID 方案把二进制切成哪几段。
 *
 * <p>前端按 {@code width} 的比例画出色带，按 {@code role} 上色 —— 把「位分段」
 * 这类纯文本知识变成可视化图形，是这一页存在的意义。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdSegmentVO {

    /** 段名，如「时间戳」「机器 ID」「序列号」 */
    private String name;

    /** 位宽（bit） */
    private int width;

    /** 语义角色，前端据此配色：SIGN / TIME / MACHINE / SEQ / RANDOM / VERSION / VARIANT / COUNTER */
    private String role;

    /** 段说明（可选，用于悬浮提示） */
    private String note;
}
