package com.serverpanel.ops.service;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.ops.dto.CronJobBody;
import com.serverpanel.ops.entity.OpsCronJob;
import com.serverpanel.ops.entity.OpsCronLog;
import com.serverpanel.ops.mapper.OpsCronJobMapper;
import com.serverpanel.ops.mapper.OpsCronLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 计划任务 Service：CRUD + 立即执行 + 调度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronJobService {

    /** 日志输出截断上限（与表注释一致 64KB） */
    private static final int MAX_LOG_OUTPUT = 64 * 1024;

    private static final CronDefinition CRON_DEF =
        CronDefinitionBuilder.instanceDefinitionFor(
            com.cronutils.model.CronType.UNIX);

    private final OpsCronJobMapper jobMapper;

    private final OpsCronLogMapper logMapper;

    private final CommandExecutor commandExecutor;

    /** 校验 cron 表达式并计算下一次执行时间 */
    public LocalDateTime computeNextRun(String cronExpr, LocalDateTime from) {
        try {
            CronParser parser = new CronParser(CRON_DEF);
            Cron cron = parser.parse(cronExpr);
            ExecutionTime et = ExecutionTime.forCron(cron);
            Optional<ZonedDateTime> next = et.nextExecution(
                from.atZone(ZoneId.systemDefault()));
            return next.map(z -> z.toLocalDateTime()).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.CRON_EXPR_INVALID);
        }
    }

    /** 解析并校验命令为 argv 数组（首项必须在白名单） */
    public String[] splitCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "命令不能为空");
        }
        String[] argv = trimmed.split("\\s+");
        if (!commandExecutor.isAllowed(argv[0])) {
            throw new ServiceException(ErrorCode.CMD_NOT_ALLOWED);
        }
        return argv;
    }

    public PageResult<OpsCronJob> page(PageQuery query, String keyword) {
        Page<OpsCronJob> page = jobMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<OpsCronJob>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(OpsCronJob::getName, keyword)
                    .or().like(OpsCronJob::getCommand, keyword))
                .orderByDesc(OpsCronJob::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    public void create(CronJobBody body) {
        splitCommand(body.getCommand());
        OpsCronJob job = new OpsCronJob();
        applyBody(job, body);
        jobMapper.insert(job);
    }

    public void update(CronJobBody body) {
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少任务 ID");
        }
        OpsCronJob db = jobMapper.selectById(body.getId());
        if (db == null) {
            throw new ServiceException(ErrorCode.CRON_JOB_NOT_FOUND);
        }
        splitCommand(body.getCommand());
        applyBody(db, body);
        jobMapper.updateById(db);
    }

    public void delete(Long id) {
        if (jobMapper.deleteById(id) == 0) {
            throw new ServiceException(ErrorCode.CRON_JOB_NOT_FOUND);
        }
        // 日志保留（冗余了任务名）
    }

    /** 立即执行（异步），返回本次日志 ID */
    public Long runNow(Long id) {
        OpsCronJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new ServiceException(ErrorCode.CRON_JOB_NOT_FOUND);
        }
        return executeJob(job);
    }

    public PageResult<OpsCronLog> logs(Long jobId, PageQuery query) {
        Page<OpsCronLog> page = logMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<OpsCronLog>()
                .eq(jobId != null, OpsCronLog::getJobId, jobId)
                .orderByDesc(OpsCronLog::getStartedAt));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 执行任务：写开始日志 → 异步执行 → 回写结果 */
    public Long executeJob(OpsCronJob job) {
        String[] argv;
        try {
            argv = splitCommand(job.getCommand());
        } catch (ServiceException e) {
            throw e;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        OpsCronLog logRow = new OpsCronLog();
        logRow.setJobId(job.getId());
        logRow.setJobName(job.getName());
        logRow.setExitCode(-1);
        logRow.setOutput("");
        logRow.setStartedAt(startedAt);
        logRow.setFinishedAt(null);
        logRow.setDurationMs(0L);
        logMapper.insert(logRow);
        final Long logId = logRow.getId();

        // 更新任务的最近执行时间
        OpsCronJob update = new OpsCronJob();
        update.setId(job.getId());
        update.setLastRunAt(startedAt);
        jobMapper.updateById(update);

        commandExecutor.execAsync(argv).whenComplete((result, ex) -> {
            OpsCronLog done = new OpsCronLog();
            done.setId(logId);
            if (ex != null) {
                done.setExitCode(-1);
                done.setOutput(truncate(ex.getMessage()));
            } else {
                done.setExitCode(result.getExitCode());
                String output = (result.getStdout() + "\n" + result.getStderr()).trim();
                done.setOutput(truncate(output));
            }
            done.setFinishedAt(LocalDateTime.now());
            done.setDurationMs(Duration.between(startedAt, LocalDateTime.now()).toMillis());
            logMapper.updateById(done);
        });
        return logId;
    }

    /** 调度器使用：找出所有待执行的启用任务 */
    public List<OpsCronJob> dueJobs(LocalDateTime now) {
        return jobMapper.selectList(new LambdaQueryWrapper<OpsCronJob>()
            .eq(OpsCronJob::getStatus, 1)
            .and(w -> w.isNull(OpsCronJob::getNextRunAt)
                .or().le(OpsCronJob::getNextRunAt, now)));
    }

    /** 调度器使用：任务执行后推进 next_run_at */
    public void advanceNextRun(OpsCronJob job) {
        LocalDateTime next = computeNextRun(job.getCronExpr(), LocalDateTime.now());
        if (next == null) {
            // 表达式在未来不再触发（如 2 月 30 日），推入远期避免每轮空转
            log.warn("cron job {} 表达式在未来不再触发，任务被挂起: {}", job.getId(), job.getCronExpr());
            next = LocalDateTime.now().plusYears(100);
        }
        OpsCronJob update = new OpsCronJob();
        update.setId(job.getId());
        update.setNextRunAt(next);
        jobMapper.updateById(update);
    }

    private void applyBody(OpsCronJob job, CronJobBody body) {
        job.setName(body.getName().trim());
        String cronExpr = body.getCronExpr().trim();
        job.setCronExpr(cronExpr);
        job.setCommand(body.getCommand().trim());
        job.setTimeoutSec(body.getTimeoutSec() == null ? 300 : body.getTimeoutSec());
        job.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        job.setRemark(body.getRemark());
        // 启用时立即计算下次执行时间
        if (job.getStatus() != null && job.getStatus() == 1) {
            job.setNextRunAt(computeNextRun(cronExpr, LocalDateTime.now()));
        } else {
            job.setNextRunAt(null);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LOG_OUTPUT ? s : s.substring(0, MAX_LOG_OUTPUT);
    }
}
