package com.serverpanel.appstack.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.config.JobProperties;
import com.serverpanel.appstack.dto.ClearLogBody;
import com.serverpanel.appstack.dto.JobLogQuery;
import com.serverpanel.appstack.entity.AppJobLog;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.mapper.AppJobLogMapper;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 调度日志服务。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobLogService {

    /** 平均耗时的采样条数上限（避免为了一个统计数字扫全表） */
    private static final int AVG_SAMPLE = 500;

    private final AppJobLogMapper logMapper;

    private final JobProperties properties;

    public PageResult<AppJobLog> page(JobLogQuery query) {
        Page<AppJobLog> page = logMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<AppJobLog>()
                        .eq(query.getJobId() != null, AppJobLog::getJobId, query.getJobId())
                        .like(query.getJobName() != null && !query.getJobName().isBlank(),
                                AppJobLog::getJobName, query.getJobName())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                                AppJobLog::getStatus, query.getStatus())
                        .eq(query.getHandler() != null && !query.getHandler().isBlank(),
                                AppJobLog::getHandler, query.getHandler())
                        .eq(query.getTriggerType() != null && !query.getTriggerType().isBlank(),
                                AppJobLog::getTriggerType, query.getTriggerType())
                        .ge(query.getBeginTime() != null,
                                AppJobLog::getTriggerTime, query.getBeginTime())
                        .le(query.getEndTime() != null,
                                AppJobLog::getTriggerTime, query.getEndTime())
                        .orderByDesc(AppJobLog::getTriggerTime));
        return PageResult.of(page.getRecords(), page.getTotal(),
                query.getPageNum(), query.getPageSize());
    }

    public AppJobLog detail(Long id) {
        AppJobLog entry = logMapper.selectById(id);
        if (entry == null) {
            throw new ServiceException(ErrorCode.JOB_LOG_NOT_FOUND);
        }
        return entry;
    }

    /** 单独取执行输出（「查看大输出」用） */
    public String output(Long id) {
        AppJobLog entry = detail(id);
        String output = entry.getExecutorOutput();
        if (output == null || output.isBlank()) {
            return "（本次执行没有输出）";
        }
        return output;
    }

    /** 顶部统计卡 */
    public Map<String, Object> statistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", logMapper.selectCount(null));
        stats.put("success", countByStatus(JobEnums.STATUS_SUCCESS));
        stats.put("failed", logMapper.selectCount(new LambdaQueryWrapper<AppJobLog>()
                .in(AppJobLog::getStatus, List.of(JobEnums.STATUS_FAILED, JobEnums.STATUS_TIMEOUT))));
        stats.put("running", countByStatus(JobEnums.STATUS_RUNNING));
        stats.put("discarded", countByStatus(JobEnums.STATUS_DISCARDED));
        stats.put("killed", countByStatus(JobEnums.STATUS_KILLED));
        stats.put("avgDurationMs", averageDuration());
        stats.put("retentionDays", properties.getLog().getRetentionDays());
        return stats;
    }

    public int retentionDays() {
        return properties.getLog().getRetentionDays();
    }

    /**
     * 按条件清理日志。
     *
     * <p><b>必须至少给出一个条件</b>：面板不提供「无条件清空全表」的入口 ——
     * 一次误点的代价是整个历史执行记录，而这类数据恰恰是事后排查的唯一依据。
     */
    public int clear(ClearLogBody body) {
        boolean hasCondition = body.getJobId() != null
                || body.getBeforeTime() != null
                || (body.getStatus() != null && !body.getStatus().isBlank());
        if (!hasCondition) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "为避免误删全表，至少要指定一个条件（任务 / 时间范围 / 状态）");
        }
        if (body.getStatus() != null && !body.getStatus().isBlank()
                && !JobEnums.STATUSES.contains(body.getStatus())) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "状态不合法：" + body.getStatus());
        }
        if (body.getConfirm() == null || !"CLEAR LOG".equals(body.getConfirm().trim())) {
            throw new ServiceException(ErrorCode.JOB_CONFIRM_REQUIRED,
                    "日志清理不可逆，请在确认框中输入：CLEAR LOG");
        }
        int deleted = logMapper.delete(new LambdaQueryWrapper<AppJobLog>()
                .eq(body.getJobId() != null, AppJobLog::getJobId, body.getJobId())
                .lt(body.getBeforeTime() != null, AppJobLog::getTriggerTime, body.getBeforeTime())
                .eq(body.getStatus() != null && !body.getStatus().isBlank(),
                        AppJobLog::getStatus, body.getStatus()));
        log.info("清理调度日志：删除 {} 条（jobId={}，before={}，status={}）",
                deleted, body.getJobId(), body.getBeforeTime(), body.getStatus());
        return deleted;
    }

    private Long countByStatus(String status) {
        return logMapper.selectCount(new LambdaQueryWrapper<AppJobLog>()
                .eq(AppJobLog::getStatus, status));
    }

    private long averageDuration() {
        List<AppJobLog> samples = logMapper.selectList(new LambdaQueryWrapper<AppJobLog>()
                .select(AppJobLog::getHandleDurationMs)
                .isNotNull(AppJobLog::getHandleDurationMs)
                .orderByDesc(AppJobLog::getTriggerTime)
                .last("LIMIT " + AVG_SAMPLE));
        if (samples.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (AppJobLog sample : samples) {
            sum += sample.getHandleDurationMs() == null ? 0L : sample.getHandleDurationMs();
        }
        return sum / samples.size();
    }
}
