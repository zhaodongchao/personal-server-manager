package com.serverpanel.system.dto.dashboard;

import lombok.Data;

/**
 * 工作台 - 访问来源（按 IP 地区聚合）。
 */
@Data
public class VisitSource {

    /** 地区标签（如 中国-广东省-深圳市 / 内网） */
    private String region;

    /** 该地区访问次数 */
    private long count;
}
