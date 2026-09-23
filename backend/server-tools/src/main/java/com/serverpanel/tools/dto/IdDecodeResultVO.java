package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * ID 反解结果。
 *
 * <p>{@code valid=false} 不是错误：ID 格式合法但版本位 / variant 位不符合该方案时，
 * 仍返回 200，用 {@code reason} 说明为什么这个 ID 不属于该方案。
 * 这能让用户区分「我抄错了 ID」和「这个 ID 根本不是这个方案生成的」。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdDecodeResultVO {

    /** 方案编码 / 展示名 */
    private String scheme;
    private String label;

    /** 规范化后的 ID（UUID 统一为小写带连字符，ObjectId 统一为小写十六进制） */
    private String value;

    /** 十六进制形态（定宽 64bit 方案给出 16 位定长十六进制；其他方案为空） */
    private String hex;

    /** 解码出的生成时间（本地时区 yyyy-MM-dd HH:mm:ss.SSS，无时间成分的方案为空） */
    private String time;

    /** 该方案的位段定义 */
    private List<IdSegmentVO> segments;

    /** 逐段取值，顺序与 {@code segments} 一致 */
    private List<String> segValues;

    /** 人类可读的事实列表（如「生成时间 2026-09-23 11:02:03.412」「机器号 17」） */
    private List<String> facts;

    /** 是否与方案匹配（版本位、variant 位、长度、位宽上限等） */
    private boolean valid;

    /** 不匹配原因（valid=true 时为「与 X 方案的格式与版本位一致」之类） */
    private String reason;
}
