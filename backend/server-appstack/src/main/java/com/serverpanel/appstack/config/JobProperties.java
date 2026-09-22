package com.serverpanel.appstack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 定时任务模块配置（application.yml 的 serverpanel.job.*）。
 *
 * <p>与 {@code MysqlAdminProperties} 同风格：{@code @Component} +
 * {@code @ConfigurationProperties}，由组件扫描直接注册。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@Component
@ConfigurationProperties(prefix = "serverpanel.job")
public class JobProperties {

    private Scheduler scheduler = new Scheduler();

    private Log log = new Log();

    private Limits limits = new Limits();

    /** 调度器开关与线程池 */
    @Data
    public static class Scheduler {

        /**
         * 总开关。置 false 时调度器完全不注册任何任务，但面板其它功能（含既有 3 处
         * {@code @Scheduled}）不受影响 —— 这是本模块**不需要改代码、不需要回滚版本**的
         * 故障处置手段（见设计 §十三 回滚）。
         */
        private boolean enabled = true;

        /**
         * 调度线程池大小。该池**只做唤醒与派发**，真实执行在独立的虚拟线程池里 ——
         * 二者必须分离：默认 poolSize=1 时若在调度线程里直接执行任务，一个 300 秒的备份
         * 会阻塞面板全部定时任务，而这类缺陷在「任务都很短」的开发环境完全不可见。
         */
        private int poolSize = 4;
    }

    /** 日志与输出 */
    @Data
    public static class Log {

        /** 日志保留天数（JOB_LOG_PURGE 内置任务使用） */
        private int retentionDays = 30;

        /** 单次执行输出入库上限（字节），超出截断 */
        private int maxOutputBytes = 65536;
    }

    /** 安全与规格上限 */
    @Data
    public static class Limits {

        /** 最小触发间隔（秒），相邻两次触发间隔小于该值直接拒绝保存 */
        private int minIntervalSeconds = 10;

        /** 执行超时上限（秒），与宿主代理 MAX_TIMEOUT=900 对齐 */
        private int maxTimeoutSeconds = 900;

        /** 失败重试次数上限 */
        private int maxRetryCount = 3;
    }
}
