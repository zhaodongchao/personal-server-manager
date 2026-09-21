package com.serverpanel.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.ops.dto.CronJobBody;
import com.serverpanel.ops.dto.CronPreviewBody;
import com.serverpanel.ops.dto.CronPreviewVO;
import com.serverpanel.ops.dto.CronSummaryVO;
import com.serverpanel.ops.dto.CronWhitelistVO;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 计划任务 Service：CRUD + 立即执行 + 调度 + 日志。
 *
 * <p>命令一律经<b>宿主执行通道</b>在宿主机上运行：面板容器（eclipse-temurin JRE）
 * 里并没有 systemctl / ufw / mysql / df 这些系统命令，在容器内执行必然失败——
 * 这正是重构前「计划管理 CRUD 可用、执行必然失败」的根因。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronJobService {

    /** 日志输出截断上限（与表注释一致 64KB） */
    private static final int MAX_LOG_OUTPUT = 64 * 1024;

    /** 退出码：成功 */
    public static final int EXIT_OK = 0;

    /** 退出码：执行异常（命令不存在、宿主通道故障等） */
    public static final int EXIT_ERROR = -1;

    /** 退出码：超时被终止（补齐表注释早已声明却从未产出的语义） */
    public static final int EXIT_TIMEOUT = -2;

    /** 退出码：并发跳过（上一次执行尚未结束） */
    public static final int EXIT_SKIPPED = -3;

    /**
     * 到期时间落后当前时间超过该秒数，即视为「错过的执行（misfire）」。
     * 留 60s 是为了容忍调度器 15s 扫描间隔与单次执行的启动开销，
     * 否则每次正常调度都会被误判成 misfire。
     */
    public static final long MISFIRE_THRESHOLD_SEC = 60L;

    /** catch_up 一次最多补跑的次数：服务停机一晚再启动时不至于引发「补跑风暴」 */
    public static final int MAX_CATCH_UP = 10;

    /** 触发方式：调度器定时触发 */
    public static final String TRIGGER_CRON = "cron";

    /** 触发方式：页面手动触发 */
    public static final String TRIGGER_MANUAL = "manual";

    /** 触发方式：misfire 补跑 */
    public static final String TRIGGER_RETRY = "retry";

    /** 错过执行策略：不补跑，直接推进到未来下一次（默认，最安全） */
    public static final String MISFIRE_SKIP = "skip";

    /** 错过执行策略：补跑一次 */
    public static final String MISFIRE_RUN_ONCE = "run_once";

    /** 错过执行策略：全补（上限 {@link #MAX_CATCH_UP}） */
    public static final String MISFIRE_CATCH_UP = "catch_up";

    /** 并发策略：已在运行则跳过本次（默认） */
    public static final String OVERLAP_SKIP = "skip";

    /** 并发策略：等待上一次结束（有上限），超时则跳过 */
    public static final String OVERLAP_QUEUE = "queue";

    /** 并发策略：不互斥，允许并行 */
    public static final String OVERLAP_PARALLEL = "parallel";

    private static final Set<String> MISFIRE_POLICIES =
            Set.of(MISFIRE_SKIP, MISFIRE_RUN_ONCE, MISFIRE_CATCH_UP);

    private static final Set<String> OVERLAP_POLICIES =
            Set.of(OVERLAP_SKIP, OVERLAP_QUEUE, OVERLAP_PARALLEL);

    /**
     * 调度抢锁时长（秒）。
     *
     * <p>正常执行结束会主动清空，这个值只是兜底：万一进程在执行中崩溃，
     * 锁也会在 2 分钟后自动失效，任务不会永久卡在「执行中」。
     * 多实例部署时靠它做乐观互斥（{@code lock_until IS NULL OR lock_until < now}）。
     */
    private static final long LOCK_SECONDS = 120L;

    /** 并发策略为 queue 时，最多等待上一次执行多久 */
    private static final long QUEUE_WAIT_MS = 60_000L;

    /** 默认单次执行超时（秒） */
    private static final long DEFAULT_TIMEOUT_SEC = 300L;

    private static final CronDefinition CRON_DEF =
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);

    private final OpsCronJobMapper jobMapper;

    private final OpsCronLogMapper logMapper;

    private final CommandExecutor commandExecutor;

    private final HostChannelService hostChannel;

    // ==================== 表达式 ====================

    /** 校验 cron 表达式并计算下一次执行时间 */
    public LocalDateTime computeNextRun(String cronExpr, LocalDateTime from) {
        try {
            CronParser parser = new CronParser(CRON_DEF);
            Cron cron = parser.parse(cronExpr);
            ExecutionTime et = ExecutionTime.forCron(cron);
            Optional<ZonedDateTime> next = et.nextExecution(from.atZone(ZoneId.systemDefault()));
            return next.map(ZonedDateTime::toLocalDateTime).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.CRON_EXPR_INVALID);
        }
    }

    /**
     * 表达式校验 + 预览：人话描述 + 未来 N 次执行时间。
     *
     * <p>表单每改一次表达式就调一次，让「填错表达式」在保存前就能被发现。
     */
    public CronPreviewVO preview(CronPreviewBody body) {
        CronPreviewVO vo = new CronPreviewVO();
        String expr = body.getCronExpr() == null ? "" : body.getCronExpr().trim();
        vo.setCronExpr(expr);
        vo.setValid(false);
        vo.setNextTimes(List.of());
        Cron cron;
        try {
            cron = new CronParser(CRON_DEF).parse(expr);
        } catch (RuntimeException e) {
            vo.setMessage("cron 表达式无效：" + e.getMessage());
            return vo;
        }
        vo.setValid(true);
        vo.setHumanExpr(describe(cron, expr));
        int count = body.getCount() == null ? 5 : body.getCount();
        List<LocalDateTime> times = new ArrayList<>(count);
        ExecutionTime et = ExecutionTime.forCron(cron);
        ZonedDateTime cursor = ZonedDateTime.now();
        for (int i = 0; i < count; i++) {
            Optional<ZonedDateTime> next = et.nextExecution(cursor);
            if (next.isEmpty()) {
                break;
            }
            times.add(next.get().toLocalDateTime());
            cursor = next.get();
        }
        vo.setNextTimes(times);
        return vo;
    }

    /** cron 表达式的人话描述；翻译失败时回退为表达式原文，绝不让保存流程失败 */
    public String describe(String cronExpr) {
        try {
            return describe(new CronParser(CRON_DEF).parse(cronExpr), cronExpr);
        } catch (RuntimeException e) {
            return cronExpr;
        }
    }

    private String describe(Cron cron, String raw) {
        try {
            String text = CronDescriptor.instance(Locale.SIMPLIFIED_CHINESE).describe(cron);
            return text == null || text.isBlank() ? raw : text.trim();
        } catch (RuntimeException e) {
            log.debug("cron 中文描述不可用，回退为表达式原文: {}", e.getMessage());
            return raw;
        }
    }

    // ==================== 命令解析 ====================

    /**
     * 把命令串解析为 argv 数组：支持 {@code "..."} 与 {@code '...'} 引号，但<b>绝不引入 shell</b>。
     *
     * <p>旧实现直接 {@code split("\\s+")}，于是 {@code sh -c "echo a b"} 这类含引号的命令
     * 会被拆成 4 段、引号还留在参数里，命令必然执行失败。这里改为引号状态机分词：
     * 引号内的空格不分词，引号本身不进入参数值，未闭合的引号直接报错。
     *
     * <p>安全边界不变：仍然只校验 argv[0] 是否在白名单内，后续参数不拼接、不解释。
     */
    public String[] splitCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "命令不能为空");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean hasToken = false;
        char quote = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (hasToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            cur.append(c);
            hasToken = true;
        }
        if (quote != 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "命令中的引号未闭合");
        }
        if (hasToken) {
            tokens.add(cur.toString());
        }
        if (tokens.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "命令不能为空");
        }
        if (!commandExecutor.isAllowed(tokens.get(0))) {
            throw new ServiceException(ErrorCode.CMD_NOT_ALLOWED);
        }
        return tokens.toArray(new String[0]);
    }

    // ==================== 查询 ====================

    public PageResult<OpsCronJob> page(PageQuery query, String keyword, Integer status,
                                       String lastResult) {
        LambdaQueryWrapper<OpsCronJob> wrapper = new LambdaQueryWrapper<OpsCronJob>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(OpsCronJob::getName, keyword)
                        .or().like(OpsCronJob::getCommand, keyword))
                .eq(status != null, OpsCronJob::getStatus, status);
        applyJobResult(wrapper, lastResult);
        wrapper.orderByDesc(OpsCronJob::getId);
        Page<OpsCronJob> page = jobMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(),
                query.getPageNum(), query.getPageSize());
    }

    /**
     * 按「最近结果」过滤任务的最近退出码。
     *
     * <p>退出码有 4 种语义（0 成功 / -1 异常 / -2 超时 / -3 并发跳过），
     * 若直接写「不等于 0」会把超时与跳过也并入失败，前端筛选就失去意义，
     * 因此这里按语义分类，而不是简单判非零。
     */
    private static void applyJobResult(LambdaQueryWrapper<OpsCronJob> wrapper, String result) {
        switch (normalizeResult(result)) {
            case "success" -> wrapper.eq(OpsCronJob::getLastExitCode, EXIT_OK);
            case "timeout" -> wrapper.eq(OpsCronJob::getLastExitCode, EXIT_TIMEOUT);
            case "skipped" -> wrapper.eq(OpsCronJob::getLastExitCode, EXIT_SKIPPED);
            case "fail" -> wrapper.ne(OpsCronJob::getLastExitCode, EXIT_OK)
                    .ne(OpsCronJob::getLastExitCode, EXIT_TIMEOUT)
                    .ne(OpsCronJob::getLastExitCode, EXIT_SKIPPED);
            default -> {
                // 未知/空筛选值：不生效，避免静默返回空列表
            }
        }
    }

    private static void applyLogResult(LambdaQueryWrapper<OpsCronLog> wrapper, String result) {
        switch (normalizeResult(result)) {
            case "success" -> wrapper.eq(OpsCronLog::getExitCode, EXIT_OK);
            case "timeout" -> wrapper.eq(OpsCronLog::getExitCode, EXIT_TIMEOUT);
            case "skipped" -> wrapper.eq(OpsCronLog::getExitCode, EXIT_SKIPPED);
            case "fail" -> wrapper.ne(OpsCronLog::getExitCode, EXIT_OK)
                    .ne(OpsCronLog::getExitCode, EXIT_TIMEOUT)
                    .ne(OpsCronLog::getExitCode, EXIT_SKIPPED);
            default -> {
                // 未知/空筛选值：不生效
            }
        }
    }

    /** 结果筛选值归一化；未识别返回空串（= 不过滤） */
    private static String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String v = result.trim().toLowerCase(Locale.ROOT);
        return Set.of("success", "timeout", "skipped", "fail").contains(v) ? v : "";
    }

    public CronSummaryVO summary() {
        CronSummaryVO vo = new CronSummaryVO();
        vo.setTotal(jobMapper.selectCount(new LambdaQueryWrapper<OpsCronJob>()));
        vo.setEnabled(jobMapper.selectCount(new LambdaQueryWrapper<OpsCronJob>()
                .eq(OpsCronJob::getStatus, 1)));
        vo.setDisabled(vo.getTotal() - vo.getEnabled());
        vo.setRunning(jobMapper.selectCount(new LambdaQueryWrapper<OpsCronJob>()
                .eq(OpsCronJob::getRunning, 1)));

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        vo.setSuccess24h(logMapper.selectCount(new LambdaQueryWrapper<OpsCronLog>()
                .ge(OpsCronLog::getStartedAt, since).eq(OpsCronLog::getExitCode, EXIT_OK)));
        vo.setFailed24h(logMapper.selectCount(new LambdaQueryWrapper<OpsCronLog>()
                .ge(OpsCronLog::getStartedAt, since).ne(OpsCronLog::getExitCode, EXIT_OK)));

        Page<OpsCronJob> lastRun = jobMapper.selectPage(new Page<>(1, 1),
                new LambdaQueryWrapper<OpsCronJob>()
                        .isNotNull(OpsCronJob::getLastRunAt)
                        .orderByDesc(OpsCronJob::getLastRunAt));
        if (!lastRun.getRecords().isEmpty()) {
            OpsCronJob job = lastRun.getRecords().get(0);
            vo.setLastRunAt(job.getLastRunAt());
            vo.setLastResult(resultName(job.getLastExitCode()));
        }
        Page<OpsCronJob> upcoming = jobMapper.selectPage(new Page<>(1, 1),
                new LambdaQueryWrapper<OpsCronJob>()
                        .eq(OpsCronJob::getStatus, 1)
                        .isNotNull(OpsCronJob::getNextRunAt)
                        .orderByAsc(OpsCronJob::getNextRunAt));
        if (!upcoming.getRecords().isEmpty()) {
            vo.setNextRunAt(upcoming.getRecords().get(0).getNextRunAt());
        }
        return vo;
    }

    /** 退出码 → 结果名（供前端着色） */
    public static String resultName(Integer exitCode) {
        if (exitCode == null) {
            return "none";
        }
        if (exitCode == EXIT_OK) {
            return "success";
        }
        if (exitCode == EXIT_TIMEOUT) {
            return "timeout";
        }
        if (exitCode == EXIT_SKIPPED) {
            return "skipped";
        }
        return "fail";
    }

    // ==================== 写操作 ====================

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
        // 日志刻意保留：它冗余了任务名，删任务不该抹掉审计线索
    }

    /** 批量删除 */
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请选择要删除的任务");
        }
        return jobMapper.deleteBatchIds(ids);
    }

    /**
     * 列表内快速启停。
     *
     * <p>启用时立即重算 next_run_at（否则该任务会一直停留在过去的时间点，
     * 下一轮扫描就被当成一大堆「错过的执行」）；停用时清空 next_run_at。
     */
    public void setStatus(Long id, Integer status) {
        OpsCronJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new ServiceException(ErrorCode.CRON_JOB_NOT_FOUND);
        }
        OpsCronJob update = new OpsCronJob();
        update.setId(id);
        update.setStatus(status);
        update.setNextRunAt(status != null && status == 1
                ? computeNextRun(job.getCronExpr(), LocalDateTime.now())
                : null);
        jobMapper.updateById(update);
    }

    private void applyBody(OpsCronJob job, CronJobBody body) {
        job.setName(body.getName().trim());
        String cronExpr = body.getCronExpr().trim();
        job.setCronExpr(cronExpr);
        job.setHumanExpr(describe(cronExpr));
        job.setCommand(body.getCommand().trim());
        job.setTimeoutSec(body.getTimeoutSec() == null ? (int) DEFAULT_TIMEOUT_SEC : body.getTimeoutSec());
        job.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        job.setMisfirePolicy(policy(body.getMisfirePolicy(), MISFIRE_POLICIES, MISFIRE_SKIP));
        job.setOverlapPolicy(policy(body.getOverlapPolicy(), OVERLAP_POLICIES, OVERLAP_SKIP));
        job.setMaxFail(body.getMaxFail() == null ? 0 : body.getMaxFail());
        job.setRemark(body.getRemark());
        // 启用时立即计算下次执行时间，避免刚保存就被判定为「错过的执行」
        if (job.getStatus() != null && job.getStatus() == 1) {
            job.setNextRunAt(computeNextRun(cronExpr, LocalDateTime.now()));
        } else {
            job.setNextRunAt(null);
        }
    }

    /** 策略值归一化：非法值一律回退默认，绝不把脏数据写进库 */
    public static String policy(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }

    /** 任务的错过执行策略（归一化后） */
    public static String misfirePolicyOf(OpsCronJob job) {
        return job == null ? MISFIRE_SKIP
                : policy(job.getMisfirePolicy(), MISFIRE_POLICIES, MISFIRE_SKIP);
    }

    /** 任务的并发策略（归一化后） */
    public static String overlapPolicyOf(OpsCronJob job) {
        return job == null ? OVERLAP_SKIP
                : policy(job.getOverlapPolicy(), OVERLAP_POLICIES, OVERLAP_SKIP);
    }

    // ==================== 执行 ====================

    /** 立即执行（异步），返回本次日志 ID；前端据此轮询直到 finishedAt 非空 */
    public Long runNow(Long id) {
        OpsCronJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new ServiceException(ErrorCode.CRON_JOB_NOT_FOUND);
        }
        return executeJob(job, TRIGGER_MANUAL, currentOperator());
    }

    /**
     * 执行一次任务：先落日志 → 按并发策略抢锁 → 虚拟线程内经宿主通道执行 → 回写结果。
     *
     * @return 本次日志 ID
     */
    public Long executeJob(OpsCronJob job, String triggerType, String operator) {
        String[] argv = splitCommand(job.getCommand());
        String overlapPolicy = policy(job.getOverlapPolicy(), OVERLAP_POLICIES, OVERLAP_SKIP);

        LocalDateTime startedAt = LocalDateTime.now();
        OpsCronLog row = new OpsCronLog();
        row.setJobId(job.getId());
        row.setJobName(job.getName());
        row.setTriggerType(triggerType == null ? TRIGGER_CRON : triggerType);
        row.setOperator(operator);
        row.setExitCode(EXIT_ERROR);
        row.setOutput("");
        row.setTimedOut(0);
        row.setTruncated(0);
        row.setStartedAt(startedAt);
        row.setDurationMs(0L);
        logMapper.insert(row);
        final Long logId = row.getId();

        OpsCronJob touch = new OpsCronJob();
        touch.setId(job.getId());
        touch.setLastRunAt(startedAt);
        jobMapper.updateById(touch);

        if (!OVERLAP_PARALLEL.equals(overlapPolicy) && !acquireSlot(job.getId(), logId, overlapPolicy)) {
            finishLog(logId, EXIT_SKIPPED,
                    "已跳过：上一次执行尚未结束（并发策略=" + overlapPolicy + "）", startedAt, false);
            return logId;
        }

        final long timeoutSec = timeoutSeconds(job);
        Thread.ofVirtual().name("cron-exec-", 0).start(() -> {
            int exit;
            boolean timedOut;
            boolean truncated;
            String output;
            try {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("argv", List.of(argv));
                args.put("timeout", timeoutSec);
                HostResult result = hostChannel.call("host.exec", args,
                        "执行计划任务 " + job.getName(), timeoutSec + 30);
                timedOut = result.isTimedOut();
                exit = timedOut ? EXIT_TIMEOUT : result.getExitCode();
                output = join(result.getStdout(), result.getStderr());
                truncated = result.isTruncated() || output.length() > MAX_LOG_OUTPUT;
            } catch (RuntimeException e) {
                log.warn("计划任务 {} 执行失败: {}", job.getName(), e.getMessage());
                exit = EXIT_ERROR;
                timedOut = false;
                truncated = false;
                output = "执行失败：" + e.getMessage();
            }
            finishLog(logId, exit, output, startedAt, timedOut, truncated);
            long duration = Duration.between(startedAt, LocalDateTime.now()).toMillis();
            releaseSlot(job.getId());
            applyOutcome(job.getId(), exit, duration);
        });
        return logId;
    }

    /**
     * 按并发策略抢占执行权。
     *
     * <p>抢锁是<b>原子</b>的：{@code UPDATE ... SET running=1 WHERE id=? AND running=0}，
     * 影响行数 0 即表示已有实例在执行。这比「先 select 再 update」可靠——
     * 后者在多实例或并发触发下存在检查与写入之间的竞态窗口。
     */
    private boolean acquireSlot(Long jobId, Long logId, String policy) {
        long deadline = System.currentTimeMillis() + QUEUE_WAIT_MS;
        while (true) {
            if (tryLock(jobId, logId)) {
                return true;
            }
            if (!OVERLAP_QUEUE.equals(policy)) {
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("计划任务 {} 等待上一次执行超时（{}ms），放弃本轮", jobId, QUEUE_WAIT_MS);
                return false;
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean tryLock(Long jobId, Long logId) {
        LambdaUpdateWrapper<OpsCronJob> uw = new LambdaUpdateWrapper<>();
        uw.set(OpsCronJob::getRunning, 1)
                .set(OpsCronJob::getRunningLogId, logId)
                .set(OpsCronJob::getLockUntil, LocalDateTime.now().plusSeconds(LOCK_SECONDS))
                .eq(OpsCronJob::getId, jobId)
                .eq(OpsCronJob::getRunning, 0);
        return jobMapper.update(null, uw) > 0;
    }

    private void releaseSlot(Long jobId) {
        LambdaUpdateWrapper<OpsCronJob> uw = new LambdaUpdateWrapper<>();
        uw.set(OpsCronJob::getRunning, 0)
                .set(OpsCronJob::getRunningLogId, null)
                .set(OpsCronJob::getLockUntil, null)
                .eq(OpsCronJob::getId, jobId);
        jobMapper.update(null, uw);
    }

    /**
     * 执行收尾：累计失败次数，达到阈值则自动停用（自愈）。
     *
     * <p>为什么需要：一个表达式写错或依赖已失效的任务，若不停用就会每天失败几十次，
     * 刷屏日志还占空间。自动停用把「持续失败」从噪音变成需要人处理的一次性事件。
     */
    private void applyOutcome(Long jobId, int exitCode, long durationMs) {
        OpsCronJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        OpsCronJob update = new OpsCronJob();
        update.setId(jobId);
        update.setLastExitCode(exitCode);
        update.setLastDurationMs(durationMs);
        int fails = exitCode == EXIT_OK ? 0 : (job.getFailCount() == null ? 0 : job.getFailCount()) + 1;
        update.setFailCount(fails);
        int maxFail = job.getMaxFail() == null ? 0 : job.getMaxFail();
        if (exitCode != EXIT_OK && maxFail > 0 && fails >= maxFail) {
            update.setStatus(0);
            update.setNextRunAt(null);
            log.warn("计划任务 [{}] 连续失败 {} 次，达到阈值 {}，已自动停用", job.getName(), fails, maxFail);
        }
        jobMapper.updateById(update);
    }

    private void finishLog(Long logId, int exitCode, String output, LocalDateTime startedAt,
                           boolean timedOut) {
        finishLog(logId, exitCode, output, startedAt, timedOut, false);
    }

    private void finishLog(Long logId, int exitCode, String output, LocalDateTime startedAt,
                           boolean timedOut, boolean truncated) {
        OpsCronLog done = new OpsCronLog();
        done.setId(logId);
        done.setExitCode(exitCode);
        done.setOutput(truncate(output));
        done.setTimedOut(timedOut ? 1 : 0);
        done.setTruncated(truncated ? 1 : 0);
        done.setFinishedAt(LocalDateTime.now());
        done.setDurationMs(Duration.between(startedAt, done.getFinishedAt()).toMillis());
        logMapper.updateById(done);
    }

    // ==================== 日志 ====================

    public PageResult<OpsCronLog> logs(Long jobId, PageQuery query, String result) {
        LambdaQueryWrapper<OpsCronLog> wrapper = new LambdaQueryWrapper<OpsCronLog>()
                .eq(OpsCronLog::getJobId, jobId)
                .orderByDesc(OpsCronLog::getStartedAt);
        applyLogResult(wrapper, result);
        Page<OpsCronLog> page = logMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(),
                query.getPageNum(), query.getPageSize());
    }

    public OpsCronLog logDetail(Long jobId, Long logId) {
        OpsCronLog row = logMapper.selectOne(new LambdaQueryWrapper<OpsCronLog>()
                .eq(OpsCronLog::getId, logId).eq(OpsCronLog::getJobId, jobId));
        if (row == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "执行日志不存在");
        }
        return row;
    }

    /** 清空某任务的执行日志 */
    public int clearLogs(Long jobId) {
        return logMapper.delete(new LambdaQueryWrapper<OpsCronLog>()
                .eq(OpsCronLog::getJobId, jobId));
    }

    /**
     * 日志保留：按时间清 + 按条数清。
     *
     * <p>只按时间清不够：一个每分钟执行的任务 30 天能产生 4 万多条，
     * 单任务的日志会无限膨胀。因此额外限制每个任务最多保留 {@code maxPerJob} 条。
     */
    public int cleanupLogs(int retainDays, int maxPerJob) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, retainDays));
        int byAge = logMapper.delete(new LambdaQueryWrapper<OpsCronLog>()
                .lt(OpsCronLog::getStartedAt, before));
        int byCount = 0;
        List<Object> ids = jobMapper.selectObjs(new LambdaQueryWrapper<OpsCronJob>()
                .select(OpsCronJob::getId));
        for (Object id : ids) {
            if (id instanceof Number num) {
                byCount += logMapper.keepLatest(num.longValue(), Math.max(1, maxPerJob));
            }
        }
        if (byAge > 0 || byCount > 0) {
            log.info("计划任务日志清理完成：按时间删除 {} 条（>{} 天），按条数删除 {} 条（每任务保留 {} 条）",
                    byAge, retainDays, byCount, maxPerJob);
        }
        return byAge + byCount;
    }

    // ==================== 调度器接口 ====================

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
            log.warn("计划任务 [{}] 的表达式在未来不再触发，任务被挂起: {}", job.getName(), job.getCronExpr());
            next = LocalDateTime.now().plusYears(100);
        }
        OpsCronJob update = new OpsCronJob();
        update.setId(job.getId());
        update.setNextRunAt(next);
        jobMapper.updateById(update);
        job.setNextRunAt(next);
    }

    /**
     * 统计「错过的执行」次数（从 next_run_at 起推算到当前时间，最多数到 limit）。
     *
     * <p>只在确认为 misfire 后才调用，正常到期不会走到这里，故开销可忽略。
     */
    public int countMissed(OpsCronJob job, int limit) {
        int count = 0;
        LocalDateTime cursor = job.getNextRunAt();
        LocalDateTime now = LocalDateTime.now();
        while (cursor != null && !cursor.isAfter(now) && count < limit) {
            count++;
            cursor = computeNextRun(job.getCronExpr(), cursor);
        }
        return count;
    }

    /** 任务单次执行的超时秒数（兜底 300s） */
    public long timeoutSeconds(OpsCronJob job) {
        if (job.getTimeoutSec() == null || job.getTimeoutSec() <= 0) {
            return DEFAULT_TIMEOUT_SEC;
        }
        return job.getTimeoutSec();
    }

    /**
     * 等待某次执行结束（misfire catch_up 串行补跑时使用）。
     *
     * @return true 表示已结束；false 表示等待超时
     */
    public boolean awaitFinish(Long logId, long timeoutSec) {
        long deadline = System.currentTimeMillis() + (timeoutSec + 15) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            OpsCronLog row = logMapper.selectById(logId);
            if (row == null || row.getFinishedAt() != null) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ==================== 白名单 ====================

    /**
     * 可执行命令白名单。
     *
     * <p>命令在<b>宿主机</b>上执行，因此这里同时给出两部分：后端允许的命令，
     * 以及其中宿主机上真实存在的（宿主通道探测结果）。前端据此标注不可用命令，
     * 避免用户选了一个「允许但宿主机没有」的命令后才发现执行失败。
     */
    public CronWhitelistVO whitelist() {
        CronWhitelistVO vo = new CronWhitelistVO();
        List<String> allowed = commandExecutor.getWhitelist().stream()
                .sorted().collect(java.util.stream.Collectors.toList());
        vo.setAllowed(allowed);
        HostCapability capability = hostChannel.capability();
        vo.setHostAvailable(capability.isOk());
        vo.setHostMessage(capability.getMessage());
        Map<String, String> tools = capability.getTools();
        if (tools == null) {
            tools = Map.of();
        }
        Map<String, String> finalTools = tools;
        vo.setAvailable(allowed.stream().filter(finalTools::containsKey)
                .collect(java.util.stream.Collectors.toList()));
        vo.setMissing(allowed.stream().filter(c -> !finalTools.containsKey(c))
                .collect(java.util.stream.Collectors.toList()));
        return vo;
    }

    // ==================== 内部工具 ====================

    private String currentOperator() {
        try {
            return LoginHelper.isLogin() ? LoginHelper.getUsername() : "system";
        } catch (RuntimeException e) {
            return "system";
        }
    }

    private static String join(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        String text = (out + "\n" + err).trim();
        return text.isEmpty() ? "（无输出）" : text;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LOG_OUTPUT ? s : s.substring(0, MAX_LOG_OUTPUT);
    }
}
