package com.serverpanel.common.job;

import java.util.List;

/**
 * 表单字段定义 —— 处理器（{@code JobHandler}）与内置任务（{@link InternalTask}）
 * 共用的参数描述单元。
 *
 * <p><b>为什么放在 server-common，而不是留在 {@code JobHandler} 里</b>：定时任务的参数
 * 表单是「后端声明、前端渲染」的，内置任务同样需要声明自己的字段（否则用户只能手写
 * JSON）。而内置任务的 SPI 位于 server-common —— 字段类型若留在 server-appstack 的
 * {@code JobHandler} 内，server-common 就得反向依赖 appstack，违反模块依赖方向。
 *
 * <p>字段被用于两处下发：
 * <ul>
 *   <li>{@code GET /appstack/job/handlers} —— 各处理器的固有字段（command / url / unit …）；</li>
 *   <li>{@code GET /appstack/job/options} 的 {@code internalTasks[].fields} —— 各内置任务的
 *       专用字段（databaseId / days …），前端按选中的任务动态渲染。</li>
 * </ul>
 *
 * @param name        参数键名
 * @param label       中文标签
 * @param type        text / number / select / textarea
 * @param required    是否必填
 * @param placeholder 占位提示
 * @param options     select 的候选项
 * @param help        字段说明
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public record JobField(String name, String label, String type, boolean required,
                       String placeholder, List<String> options, String help) {

    /** 单行文本 */
    public static JobField text(String name, String label, boolean required, String help) {
        return new JobField(name, label, "text", required, null, List.of(), help);
    }

    /** 数字输入 */
    public static JobField number(String name, String label, boolean required, String help) {
        return new JobField(name, label, "number", required, null, List.of(), help);
    }

    /** 多行文本 */
    public static JobField area(String name, String label, boolean required, String help) {
        return new JobField(name, label, "textarea", required, null, List.of(), help);
    }

    /** 下拉选择 */
    public static JobField select(String name, String label, boolean required,
                                  List<String> options, String help) {
        return new JobField(name, label, "select", required, null, options, help);
    }
}
