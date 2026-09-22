package com.serverpanel.appstack.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 定时任务执行器（app_executor）。
 *
 * <p>两类执行器共用一张表（设计 ADR-1）：
 * <ul>
 *   <li>{@code BUILTIN} —— 面板自身（{@code app_name = serverpanel-builtin}），
 *       随 V11 seed，**不可删除、不可停用**，调度时直接在本进程派发；</li>
 *   <li>{@code HTTP} —— 手工登记的外部执行器，调度时 {@code POST {base_url}/run}。</li>
 * </ul>
 *
 * <p>保留「任务必须归属执行器」这一 xxl-job 的结构性约束，但不引入它的注册中心与心跳协议
 * —— 那是为「上万执行器」的运维成本设计的，放在单机面板上是负收益。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_executor")
public class AppExecutor extends BaseEntity {

    /** 主键需要序列化为字符串（19 位雪花 ID 超 JS 安全整数）；基类字段只能在 getter 上覆写 */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 执行器 AppName（唯一） */
    private String appName;

    /** 显示名 */
    private String executorName;

    /** BUILTIN 内置 / HTTP 外部 */
    private String type;

    /** 外部执行器基地址（内置固定为 local） */
    private String baseUrl;

    /** 外部执行器鉴权令牌（出参一律掩码，不回显明文） */
    private String authToken;

    /** AVAILABLE / UNREACHABLE / DISABLED */
    private String status;

    /** 连续探测/调用失败次数（熔断计数） */
    private Integer failStreak;

    /** 最近一次探测成功时间 */
    private LocalDateTime lastBeatAt;

    /** 最近一次失败原因 */
    private String lastError;

    /** 备注 */
    private String remark;
}
