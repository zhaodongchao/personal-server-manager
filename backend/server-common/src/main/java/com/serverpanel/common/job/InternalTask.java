package com.serverpanel.common.job;

import java.util.List;
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
     * 界面表单字段（可选，默认空）。
     *
     * <p>默认返回空列表 —— 此时界面只给一个「参数（JSON 对象）」文本域，用户按
     * {@link #description()} 的说明手写 JSON。返回非空则界面按这些字段渲染结构化表单，
     * 填写结果作为 {@code params} 下发给 {@link #execute(Map)}。
     *
     * <p><b>声明了字段的任务，界面会收起自由 JSON 文本域</b>（两者并存会让同名字段出现
     * 两个入口，保存时无法判断该信谁）—— 所以需要接收什么参数就在这里声明全。
     * 字段名不能是 {@code task} 或 {@code params}（与处理器固有字段冲突，前端会忽略）。
     *
     * @return 字段定义；默认空
     *
     * @author zhaodc
     * @since 2026-09-23 UTC+8
     */
    default List<JobField> fields() {
        return List.of();
    }

    /**
     * 是否接受自由 JSON 参数（仅在 {@link #fields()} 为空时有意义）。
     *
     * <p>默认 {@code true} —— 保留那个可选的「参数（JSON 对象）」输入框，让不声明字段的
     * 任务也能传参。声明「本任务没有参数」的任务返回 {@code false}，界面就不再渲染它：
     * 一个永远不该填的输入框，只会让人犹豫该填什么。
     *
     * @return 是否显示自由参数输入框
     *
     * @author zhaodc
     * @since 2026-09-23 UTC+8
     */
    default boolean freeFormParams() {
        return true;
    }

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
