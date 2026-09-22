package com.serverpanel.appstack.job.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.common.job.InternalTask;

import lombok.extern.slf4j.Slf4j;

/**
 * 面板内置任务注册表。
 *
 * <p>收集**所有模块**贡献的 {@link InternalTask} 实现（Spring 注入 {@code List<InternalTask>}）：
 * 库备份来自应用栈、回收站清理来自文件模块、证书续期来自运维工具。
 * SPI 定义在 server-common，各模块各自实现，**没有横向依赖**（设计 ADR-2）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
public class InternalTaskRegistry {

    private final Map<String, InternalTask> tasks;

    public InternalTaskRegistry(List<InternalTask> beans) {
        Map<String, InternalTask> map = new LinkedHashMap<>();
        for (InternalTask task : beans) {
            InternalTask previous = map.put(task.code(), task);
            if (previous != null) {
                log.warn("内置任务编码重复：{}（{} 覆盖 {}）", task.code(),
                        task.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        this.tasks = Map.copyOf(map);
        log.info("面板内置任务已装载：{}", this.tasks.keySet());
    }

    /** 按编码取任务；不存在返回 null */
    public InternalTask get(String code) {
        return code == null ? null : tasks.get(code);
    }

    /** 全部内置任务 */
    public List<InternalTask> all() {
        return List.copyOf(tasks.values());
    }
}
