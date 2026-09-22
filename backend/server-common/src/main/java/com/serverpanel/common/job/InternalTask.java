package com.serverpanel.common.job;

import java.util.Map;

/**
 * 面板内置任务 SPI —— 定时任务模块中「最安全的一档」任务类型。
 *
 * <p>执行体是面板内的受控 bean：无外部命令、无任意网络、参数强类型，因此它承担了
 * 「表达力」的兜底角色（用白名单命令 + 内置任务两档替代 xxl-job 的在线编码 GLUE）。
 *
 * <p><b>为什么 SPI 放在 server-common</b>：内置任务天然属于各个业务模块 ——
 * 清理回收站属 {@code server-file}、Nginx 证书续期属 {@code server-ops}、库备份属
 * {@code server-appstack}。若把 SPI 定义在 appstack，其它模块就得反向依赖它；
 * 定义在 common 则各模块**各自贡献**实现（{@code @Component}），由
 * {@code InternalTaskRegistry} 统一收集，既无横向依赖又能复用既有能力（设计 ADR-2）。
 *
 * <p>实现约定：execute 内部自行捕获可预期异常并返回 {@code Result.fail}，
 * 未捕获异常会被 handler 兜底记为执行失败。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public interface InternalTask {

    /** 任务编码（全局唯一，如 DB_BACKUP） */
    String code();

    /** 显示名 */
    String label();

    /** 用途说明（前端展示） */
    String description();

    /**
     * 执行。
     *
     * @param params 任务参数（字符串键值，由用户在任务里填写）
     * @return 执行结果
     */
    Result execute(Map<String, String> params);

    /**
     * 执行结果。
     *
     * @param success 是否成功
     * @param message 结果摘要（入 handle_msg）
     * @param output  详细输出（入 executor_output，超长会被截断）
     */
    record Result(boolean success, String message, String output) {

        public static Result ok(String message, String output) {
            return new Result(true, message, output);
        }

        public static Result ok(String message) {
            return new Result(true, message, null);
        }

        public static Result fail(String message) {
            return new Result(false, message, null);
        }

        /**
         * 失败结果（带详细输出）。
         *
         * <p>失败路径同样需要 output：批量任务「部分成功」时，失败明细必须能落到
         * 日志的 executor_output 里，否则用户只看到一句汇总，无从排查是哪一项挂了。
         */
        public static Result fail(String message, String output) {
            return new Result(false, message, output);
        }
    }
}
