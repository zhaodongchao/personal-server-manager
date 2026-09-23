package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * 单条生成结果。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdItemVO {

    /** 序号（从 1 开始，仅用于界面标序） */
    private int index;

    /** ID 文本：定宽整数类为十进制长整数，UUID 为 8-4-4-4-12 规范串，ObjectId 为 24 位十六进制 */
    private String value;

    /** 十六进制形态（定宽 64bit 方案给出 16 位定长十六进制，便于对照位段；其他方案为空） */
    private String hex;

    /** 解码出的生成时间（本地时区 yyyy-MM-dd HH:mm:ss.SSS，无时间成分的方案为空） */
    private String time;

    /** 附加说明（如序列的会话号与缓存段起点、ObjectId 的计数器值、雪花的时间回拨策略命中情况） */
    private String extra;

    /** 逐段取值，顺序与 {@link IdGenerateResultVO#getSegments()} 一致；超出 segLimit 时为 null */
    private List<String> segValues;
}
