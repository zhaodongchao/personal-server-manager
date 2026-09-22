package com.serverpanel.appstack.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 定时任务定义（app_job）。
 *
 * <p>字段取 xxl-job {@code JobInfo} 的主干：执行器归属 / handler / 参数 / cron /
 * 路由与阻塞策略 / 超时 / 重试 / 启停。其中「阻塞策略」与「超时」是必须有的 ——
 * 它们直接决定「任务还没跑完又到点了」与「任务跑挂了」这两类真实故障的行为。
 *
 * <p>{@code handler_param} 在库中是 JSON 列，本实体以 String 承载：
 * 参数的解析与拼装统一走 {@code JobSupport}（Jackson 3），避免依赖 MyBatis-Plus 的
 * Jackson2 系 typeHandler（本项目序列化栈是 Jackson 3 / tools.jackson）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_job")
public class AppJob extends BaseEntity {

    /** 主键需要序列化为字符串（19 位雪花 ID 超 JS 安全整数） */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 任务名（唯一） */
    private String jobName;

    /** 任务描述 */
    private String jobDesc;

    /** 执行器 ID → app_executor.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long executorId;

    /** SHELL / HTTP / SERVICE / INTERNAL */
    private String handler;

    /** 处理器参数（JSON 原文，按 handler 各自 schema） */
    private String handlerParam;

    /** cron 表达式（6 段：秒 分 时 日 月 周） */
    private String cronExpr;

    /** FIRST / ROUND / RANDOM / FAILOVER（内置执行器单实例时策略退化为空操作，保留字段以兼容扩展） */
    private String routeStrategy;

    /** SERIAL / DISCARD_LATER / COVER_EARLY */
    private String blockStrategy;

    /** 执行超时秒数（上限 900，与宿主代理 MAX_TIMEOUT 对齐） */
    private Integer timeoutSec;

    /** 失败重试次数（0~3，固定 5s 间隔） */
    private Integer retryCount;

    /** 0 停用 / 1 启用 */
    private Integer status;

    /** L3 任务需校验的确认关键字（命中服务保护清单的破坏性动作） */
    private String confirmKeyword;

    /** 下次触发时间（调度器回写，列表直接展示） */
    private LocalDateTime nextFireTime;

    /** 上次触发时间 */
    private LocalDateTime lastFireTime;

    /** 上次结果 RUNNING/SUCCESS/FAILED/TIMEOUT/DISCARDED/KILLED */
    private String lastStatus;

    /** 连续失败次数（列表告警用） */
    private Integer failStreak;

    /** 创建人 user_id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long owner;
}
