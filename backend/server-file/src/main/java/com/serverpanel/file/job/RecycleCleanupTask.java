package com.serverpanel.file.job;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.common.job.InternalTask;
import com.serverpanel.file.service.RecycleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置任务：清理过期回收站文件。
 *
 * <p>复用 {@code server-file} 既有的 {@link RecycleService#cleanupExpired()}（当前由
 * 硬编码 {@code @Scheduled(cron = "0 30 3 * * *")} 触发）。本类**只是再暴露一个
 * 可手动/按自定义 cron 触发的入口**，两条路径并存、互不干扰；既有 {@code @Scheduled}
 * 本次刻意不改（迁移会引入回归面，收益只是「可配置」）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleCleanupTask implements InternalTask {

    private final RecycleService recycleService;

    @Override
    public String code() {
        return "RECYCLE_CLEANUP";
    }

    @Override
    public String label() {
        return "清理过期回收站文件";
    }

    @Override
    public String description() {
        return "按保留天数清理回收站中已过期的条目（等价于面板每日 03:30 的内置清理）";
    }

    @Override
    public Result execute(Map<String, String> params) {
        recycleService.cleanupExpired();
        return Result.ok("回收站过期条目清理完成");
    }
}
