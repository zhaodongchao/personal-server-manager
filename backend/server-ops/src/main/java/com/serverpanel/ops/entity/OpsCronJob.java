package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 计划任务。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_cron_job")
public class OpsCronJob extends BaseEntity {

    private String name;

    /** 5 段 unix cron 表达式 */
    private String cronExpr;

    /** cron 表达式的人话描述（缓存，保存时由 cron-utils 生成） */
    private String humanExpr;

    /** 命令（空格分隔的 argv，首项须在白名单内；支持 "..." / '...' 引号） */
    private String command;

    private Integer timeoutSec;

    /** 1 启用 0 停用 */
    private Integer status;

    /** 错过执行（misfire）策略：skip / run_once / catch_up */
    private String misfirePolicy;

    /** 并发策略：skip / queue / parallel */
    private String overlapPolicy;

    /** 连续失败次数（成功归零） */
    private Integer failCount;

    /** 连续失败自动停用阈值，0 表示不自动停用 */
    private Integer maxFail;

    /** 最近一次退出码（0 成功 / -1 异常 / -2 超时 / -3 已跳过） */
    private Integer lastExitCode;

    /** 最近一次耗时（毫秒） */
    private Long lastDurationMs;

    /** 1 表示正在执行，用于并发互斥 */
    private Integer running;

    /** 当前这次执行对应的日志 ID */
    private Long runningLogId;

    /**
     * 调度抢锁到期时间（多实例安全）。
     *
     * <p>调度器拿到执行权时写入 now+lockSeconds；执行结束清空。
     * 抢锁条件为 {@code lock_until IS NULL OR lock_until < now}，
     * 这样即便部署了多个后端实例，同一任务也只会被一个实例调度。
     */
    private LocalDateTime lockUntil;

    private String remark;

    private LocalDateTime lastRunAt;

    /** 调度器计算的下次执行时间 */
    private LocalDateTime nextRunAt;
}
