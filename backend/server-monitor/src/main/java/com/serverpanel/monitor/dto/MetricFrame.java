package com.serverpanel.monitor.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 实时监控帧（Redis 环形缓存 & WebSocket 推送载体）。
 */
@Data
public class MetricFrame implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 采集时间戳（毫秒） */
    private long ts;

    /** 已运行秒数 */
    private long uptimeSeconds;

    /** CPU 使用率 0-100 */
    private double cpuUsage;

    /** 内存总量（字节） */
    private long memTotal;

    /** 内存已用（字节） */
    private long memUsed;

    /** 内存使用率 0-100 */
    private double memUsage;

    /** 1 分钟负载 */
    private double loadAvg1;

    /** 5 分钟负载 */
    private double loadAvg5;

    /** 15 分钟负载 */
    private double loadAvg15;

    /** 网络入速率 KB/s */
    private double netInRate;

    /** 网络出速率 KB/s */
    private double netOutRate;

    /** 根分区总量（字节） */
    private long diskTotal;

    /** 根分区已用（字节） */
    private long diskUsed;

    /** 根分区使用率 0-100 */
    private double diskUsage;
}
