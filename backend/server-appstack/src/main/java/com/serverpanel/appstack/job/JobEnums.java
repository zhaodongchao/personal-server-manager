package com.serverpanel.appstack.job;

import java.util.List;
import java.util.Set;

/**
 * 定时任务模块的枚举字面量集中定义。
 *
 * <p>库中这些维度都以字符串/小整数存储（便于人工排查与后续扩展），故不用 Java enum 承载，
 * 避免出现「库里是 A 枚举里没有」的静默不一致。校验一律走这里的 Set。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public final class JobEnums {

    private JobEnums() {}

    // ==================== 执行器 ====================

    /** 内置执行器 AppName（随 V11 seed，不可删除、不可停用） */
    public static final String BUILTIN_APP_NAME = "serverpanel-builtin";

    public static final String TYPE_BUILTIN = "BUILTIN";

    public static final String TYPE_HTTP = "HTTP";

    public static final Set<String> EXECUTOR_TYPES = Set.of(TYPE_BUILTIN, TYPE_HTTP);

    public static final String EXEC_AVAILABLE = "AVAILABLE";

    public static final String EXEC_UNREACHABLE = "UNREACHABLE";

    public static final String EXEC_DISABLED = "DISABLED";

    public static final Set<String> EXECUTOR_STATUSES =
            Set.of(EXEC_AVAILABLE, EXEC_UNREACHABLE, EXEC_DISABLED);

    /** 外部执行器连续失败达到该次数即熔断为 UNREACHABLE，不再发起网络请求 */
    public static final int EXECUTOR_FAIL_THRESHOLD = 3;

    // ==================== 任务处理器 ====================

    public static final String HANDLER_SHELL = "SHELL";

    public static final String HANDLER_HTTP = "HTTP";

    public static final String HANDLER_SERVICE = "SERVICE";

    public static final String HANDLER_INTERNAL = "INTERNAL";

    public static final Set<String> HANDLERS =
            Set.of(HANDLER_SHELL, HANDLER_HTTP, HANDLER_SERVICE, HANDLER_INTERNAL);

    // ==================== 阻塞策略 ====================

    /** 前次未结束则本轮等待（最多等 timeoutSec），适合备份、导出等不可并发的任务 */
    public static final String BLOCK_SERIAL = "SERIAL";

    /** 前次未结束则本轮直接丢弃，适合「错过就错过」的对账/报表 */
    public static final String BLOCK_DISCARD_LATER = "DISCARD_LATER";

    /** 终止前次、放行本轮，适合只关心最新状态的同步任务 */
    public static final String BLOCK_COVER_EARLY = "COVER_EARLY";

    public static final Set<String> BLOCK_STRATEGIES =
            Set.of(BLOCK_SERIAL, BLOCK_DISCARD_LATER, BLOCK_COVER_EARLY);

    // ==================== 路由策略 ====================

    public static final String ROUTE_FIRST = "FIRST";

    public static final String ROUTE_ROUND = "ROUND";

    public static final String ROUTE_RANDOM = "RANDOM";

    public static final String ROUTE_FAILOVER = "FAILOVER";

    public static final Set<String> ROUTE_STRATEGIES =
            Set.of(ROUTE_FIRST, ROUTE_ROUND, ROUTE_RANDOM, ROUTE_FAILOVER);

    // ==================== 触发方式 ====================

    public static final String TRIGGER_CRON = "CRON";

    public static final String TRIGGER_MANUAL = "MANUAL";

    // ==================== 执行状态 ====================

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public static final String STATUS_DISCARDED = "DISCARDED";

    public static final String STATUS_KILLED = "KILLED";

    public static final Set<String> STATUSES = Set.of(
            STATUS_RUNNING, STATUS_SUCCESS, STATUS_FAILED,
            STATUS_TIMEOUT, STATUS_DISCARDED, STATUS_KILLED);

    // ==================== 调度侧 / 执行侧码 ====================

    /** 已派发 */
    public static final int TRIGGER_DISPATCHED = 0;

    /** 任务已不存在 */
    public static final int TRIGGER_JOB_MISSING = 404;

    /** 派发失败（执行器不可达 / 被阻塞策略丢弃） */
    public static final int TRIGGER_FAILED = 500;

    public static final int HANDLE_SUCCESS = 0;

    public static final int HANDLE_FAILED = 500;

    // ==================== 服务动作 ====================

    /** 宿主代理 service.action 支持的动作集合（与 hostagent 的 SERVICE_ACTIONS 保持一致） */
    public static final Set<String> SERVICE_ACTIONS = Set.of(
            "start", "stop", "restart", "reload", "try-restart",
            "enable", "disable", "mask", "unmask", "reset-failed", "kill");

    /**
     * 破坏性动作 —— 命中服务保护清单时需 L3 确认关键字。
     *
     * <p>{@code ssh.service} 的 stop/restart、{@code psm-hostagent.service} 的任意停止动作
     * 都是「自杀式自锁」：下令之后面板自己也失联了，没有第二次机会。
     */
    public static final List<String> DESTRUCTIVE_SERVICE_ACTIONS =
            List.of("stop", "restart", "try-restart", "disable", "mask", "kill");

    /** 是否破坏性服务动作 */
    public static boolean isDestructiveServiceAction(String action) {
        return action != null && DESTRUCTIVE_SERVICE_ACTIONS.contains(action);
    }
}
