package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一种 ID 生成方案的完整描述。
 *
 * <p>方案清单是服务端的唯一真源：位分配、参数定义、优缺点全部下发，前端不硬编码
 * —— 与加解密页、混淆页、定时任务的 schema 驱动口径一致。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdSchemeVO {

    /** 方案编码，如 SNOWFLAKE / UUID_V7 */
    private String value;

    /** 界面展示名 */
    private String label;

    /** 所属大类：DB / RANDOM / SNOWFLAKE */
    private String group;

    /** 大类展示名 */
    private String groupLabel;

    /** 位分配公式，如「1+41+10+12」；非定宽方案（自增、序列）为空串 */
    private String bits;

    /** 定宽总位数；非定宽方案为 0 */
    private int totalBits;

    /** 输出形态：NUMBER（十进制长整数）/ STRING（十六进制串）/ HEX（24 位十六进制） */
    private String shape;

    /** 单调性描述，如「单调递增」「时间有序（大体有序）」「完全无序」 */
    private String ordered;

    /** 生成方描述，如「数据库端」「应用端（驱动）」「应用端（本地计算）」 */
    private String generator;

    /** 一句话定位 */
    private String note;

    /** 优点 */
    private List<String> pros;

    /** 缺点与风险 */
    private List<String> cons;

    /** 位段定义（可视化用） */
    private List<IdSegmentVO> segments;

    /** 参数定义（表单渲染用） */
    private List<IdParamVO> params;

    /** 示例（静态文案，便于用户在生成前先看懂长什么样） */
    private String sample;

    /** 是否含时间成分 —— 决定结果表要不要展示「解码时间」列 */
    private boolean timeBased;

    /** 该类方案是否需要「多台机器各自取号」的说明（雪花类为 true） */
    private boolean distributed;
}
