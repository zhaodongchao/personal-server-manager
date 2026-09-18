package com.serverpanel.monitor.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监控小时聚合 mon_metric_hour。
 */
@Data
@TableName("mon_metric_hour")
public class MonMetricHour implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 统计小时（整点） */
    private LocalDateTime metricTime;

    private BigDecimal cpuUsage;

    private BigDecimal memUsage;

    private BigDecimal diskUsage;

    /** 小时流入 MB */
    private BigDecimal netInMb;

    /** 小时流出 MB */
    private BigDecimal netOutMb;

    /** 1 分钟负载均值 */
    private BigDecimal loadAvg;
}
