package com.serverpanel.tools.service;

import com.serverpanel.common.job.InternalTask;
import com.serverpanel.tools.service.BasedataSyncService;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置任务：基础数据周同步（行政区划 / 号段 / 银行卡 BIN）。
 *
 * <p>实现 {@link InternalTask} SPI（server-common 定义），由调度中心的
 * INTERNAL 处理器统一路由 —— 不为它新增 handler 分支：既有 SPI 本来就是
 * 「各业务模块贡献内置任务」的设计，本任务只是又一个贡献者。
 * 种子任务见 V21 迁移（每周日凌晨 4 点，cron 可在调度中心调整）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolsBasedataSyncTask implements InternalTask {

    private final BasedataSyncService basedataSyncService;

    @Override
    public String code() {
        return "TOOLS_BASEDATA_SYNC";
    }

    @Override
    public String label() {
        return "基础数据周同步";
    }

    @Override
    public String description() {
        return "同步行政区划（4 级）/ 手机号段 / 银行卡 BIN；"
                + "数据源与开关见系统管理-参数配置（tools.basedata.*）";
    }

    /**
     * 无参数任务：收起自由 JSON 输入框，避免「填了也不生效」的误导。
     */
    @Override
    public boolean freeFormParams() {
        return false;
    }

    @Override
    public Result execute(java.util.Map<String, String> params) {
        try {
            String summary = basedataSyncService.syncAll();
            return Result.ok("同步完成", summary);
        } catch (RuntimeException e) {
            log.warn("基础数据同步任务失败：{}", e.getMessage());
            return Result.fail("同步失败：" + e.getMessage());
        }
    }
}
