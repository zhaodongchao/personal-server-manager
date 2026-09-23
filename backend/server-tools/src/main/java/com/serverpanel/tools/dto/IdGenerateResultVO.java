package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * ID 生成结果。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdGenerateResultVO {

    /** 方案编码 / 展示名 / 大类 */
    private String scheme;
    private String label;
    private String group;
    private String groupLabel;

    /** 位分配公式与总位数（回显，便于结果区直接展示） */
    private String bits;
    private int totalBits;

    /** 输出形态 / 单调性 / 生成方 */
    private String shape;
    private String ordered;
    private String generator;

    /** 实际生成条数 */
    private int count;

    /** 生成结果 */
    private List<IdItemVO> ids;

    /** 位段定义（与 options 中一致，便于结果区独立渲染） */
    private List<IdSegmentVO> segments;

    /** 是否携带了逐条位段拆解（条数超过 segLimit 时为 false） */
    private boolean segValuesIncluded;

    /** 逐条位段拆解的条数上限 */
    private int segLimit;

    /** 方案级提示（如「时间基准 2010-11-04 15:54:57.657，41 位毫秒可用约 69.7 年」） */
    private List<String> notes;

    /** 告警（时钟回拨命中、机器号接近位宽上限、UUIDv1 使用随机 node 等） */
    private List<String> warnings;

    /** 生成耗时（毫秒） */
    private long elapsedMs;
}
