package com.serverpanel.appstack.job;

import java.util.List;
import java.util.Map;

import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.common.job.JobField;

/**
 * 任务处理器 SPI —— 4 类任务：{@code SHELL / HTTP / SERVICE / INTERNAL}。
 *
 * <p>{@link #schema()} 是本设计的要点：{@code GET /appstack/job/handlers} 把 4 个 handler 的
 * 类型、显示名与表单字段定义一次性下发，前端表单**按 schema 动态渲染** ——
 * 这避免了「后端加了参数、前端表单没跟上」的经典漂移。
 *
 * <p>{@code INTERNAL} 还有第二层动态：具体内置任务用
 * {@link com.serverpanel.common.job.InternalTask#fields()} 声明自己的专用字段，经
 * {@code GET /appstack/job/options} 的 {@code internalTasks[].fields} 下发，由前端
 * 按选中的任务再渲染一次。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public interface JobHandler {

    /** 处理器类型（与 {@link JobEnums#HANDLERS} 一致） */
    String type();

    /** 表单 schema（前端据此渲染参数表单） */
    HandlerSchema schema();

    /**
     * 参数静态校验，保存任务时调用。
     *
     * <p>不通过直接抛 {@code ServiceException}，错误码由各 handler 按**语义**选择：
     * 白名单违规用 6036、高危及需确认用 6039、其余参数问题用 6033。
     * 这样「为什么被拒」在接口层面就是可分辨的，而不是一律 6033。
     *
     * <p>在保存时就校验，避免把非法参数落到库里、等到执行时才炸。
     *
     * @throws com.serverpanel.common.exception.ServiceException 参数不合法
     */
    default void validate(Map<String, Object> param, AppJob job) {
        // 无参处理器无需校验
    }

    /** 执行一次（可能被重试多次调用） */
    JobExecuteResult execute(JobContext ctx);

    /**
     * 处理器表单 schema。
     *
     * <p>字段类型见 {@link JobField}（定义在 server-common —— 内置任务的 SPI 也在那里，
     * 字段类型不上提的话 server-common 就得反向依赖本模块）。
     *
     * @param type        处理器类型
     * @param label       显示名
     * @param description 用途说明
     * @param fields      字段定义
     */
    record HandlerSchema(String type, String label, String description, List<JobField> fields) {}
}
