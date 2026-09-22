package com.serverpanel.appstack.job.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.job.JobLogRecorder;
import com.serverpanel.common.job.InternalTask;
import com.serverpanel.common.job.JobField;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置任务：清理超期调度日志。
 *
 * <p>默认保留 {@code serverpanel.job.log.retention-days}（30）天；也可用参数
 * {@code days} 覆盖。日志表是本模块唯一会持续膨胀的表，靠这里做日常治理，
 * 配合 {@code idx_trigger_time} 索引避免每次清理都全表扫。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLogPurgeTask implements InternalTask {

    private final JobLogRecorder recorder;

    private final JobProperties properties;

    @Override
    public String code() {
        return "JOB_LOG_PURGE";
    }

    @Override
    public String label() {
        return "清理超期调度日志";
    }

    @Override
    public String description() {
        return "按保留天数清理历史调度日志（默认 "
                + "30 天，可用参数 days 覆盖）";
    }

    /**
     * 界面字段：保留天数。
     *
     * <p>help 里带上当前生效的配置值 —— 用户不填时到底按多少天清理，保存前就能看见。
     *
     * @author zhaodc
     * @since 2026-09-23 UTC+8
     */
    @Override
    public List<JobField> fields() {
        return List.of(JobField.number("days", "保留天数", false,
                "留空则使用系统配置（当前 " + properties.getLog().getRetentionDays() + " 天）"));
    }

    @Override
    public Result execute(Map<String, String> params) {
        int days = properties.getLog().getRetentionDays();
        String override = params.get("days");
        if (override != null && !override.isBlank()) {
            try {
                days = Math.max(1, Integer.parseInt(override.trim()));
            } catch (NumberFormatException e) {
                return Result.fail("days 不是合法数字：" + override);
            }
        }
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        int deleted = recorder.purge(before, null, null);
        return Result.ok("已清理 " + deleted + " 条 " + days + " 天前的调度日志");
    }
}
