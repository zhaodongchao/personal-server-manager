package com.serverpanel.ops.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.ops.constant.ProtectedUnits;
import com.serverpanel.ops.dto.ServiceActionBody;
import com.serverpanel.ops.dto.ServiceActionResultVO;
import com.serverpanel.ops.dto.ServiceBatchBody;
import com.serverpanel.ops.dto.ServiceBatchResultVO;
import com.serverpanel.ops.dto.ServiceDetailVO;
import com.serverpanel.ops.dto.ServiceSummaryVO;
import com.serverpanel.ops.dto.ServiceVO;
import com.serverpanel.ops.dto.SysLogLine;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * systemd 服务管理。
 *
 * <p><b>2026-09-21 重构要点</b>：
 * <ol>
 *   <li><b>能看全</b>：以 {@code list-unit-files}（已安装全集）为主表，而不是旧实现的
 *       {@code list-units}（仅当前加载的）。旧实现在本机会漏掉 58 个已安装但未加载的单元。</li>
 *   <li><b>走宿主通道</b>：所有系统命令经 {@link HostChannelService} 交给宿主机
 *       psm-hostagent 执行（容器内没有 systemctl）。</li>
 *   <li><b>动作后回读</b>：不再只信退出码，每个动作结束都回读 is-active / is-enabled。</li>
 *   <li><b>危险分级</b>：保护清单内单元的 stop/restart/disable/kill 与 mask/unmask 升为
 *       L3，要求二次确认（键入单元名）。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceService {

    /**
     * 允许的 unit 名。
     *
     * <p>systemd 会把名字里的 `-` 转义为 `\x2d`、`/` 转义为 `-`，因此**合法 unit 名可以含
     * 反斜杠**（本机实例：systemd-fsck@dev-debian\x2dvg-docker_data.service），且根挂载单元
     * `-.mount` 以 `-` 开头，故不能假设首字符必为字母数字。仍严格排除空格、引号、`;`、`|`、
     * `$`、`*`、`?`、`[`、`]`、`/`，杜绝参数注入与路径穿越；并保留 `.service` 后缀约束，
     * 因为本页只管理 service 类单元。
     */
    private static final Pattern UNIT_NAME =
            Pattern.compile("^[A-Za-z0-9_@\\\\-][A-Za-z0-9_.@:\\\\-]{0,250}\\.service$");

    /** 面板允许的 systemctl 子命令 */
    private static final Set<String> ACTIONS = Set.of(
            "start", "stop", "restart", "reload", "try-restart",
            "enable", "disable", "mask", "unmask", "reset-failed", "kill");

    /** 低危动作（即便命中断言清单也不升 L3） */
    private static final Set<String> SAFE_ACTIONS = Set.of(
            "start", "reload", "try-restart", "reset-failed");

    /** 需要随后 daemon-reload 的动作 */
    private static final Set<String> RELOAD_AFTER = Set.of("enable", "disable", "mask", "unmask");

    private static final Set<String> SIGNALS = Set.of(
            "SIGHUP", "SIGINT", "SIGTERM", "SIGKILL", "SIGUSR1", "SIGUSR2", "SIGQUIT");

    /** 列表用轻量属性集（批量一次取回，避免 N 次单查） */
    private static final List<String> LIST_PROPERTIES = List.of(
            "Id", "Names", "MainPID", "MemoryCurrent", "ActiveEnterTimestamp",
            "ActiveEnterTimestampMonotonic", "StateChangeTimestamp",
            "StateChangeTimestampMonotonic", "NRestarts", "FragmentPath",
            "LoadState", "ActiveState", "SubState", "UnitFileState", "Description");

    /** 详情用完整属性集（含依赖关系） */
    private static final List<String> DETAIL_PROPERTIES = List.of(
            "Id", "Names", "MainPID", "MemoryCurrent", "CPUUsageNSec",
            "ActiveEnterTimestamp", "ActiveEnterTimestampMonotonic",
            "StateChangeTimestamp", "StateChangeTimestampMonotonic",
            "NRestarts", "FragmentPath", "LoadState", "ActiveState", "SubState",
            "UnitFileState", "Description", "ExecStart", "WantedBy", "RequiredBy",
            "Requires", "After");

    /** systemctl show 单次最多带多少 unit（控制命令行长度与输出体量） */
    private static final int SHOW_CHUNK_SIZE = 80;

    private static final int MAX_LOG_LINES = 2000;

    /** 快照缓存时长：30s。列表来自 3~4 次 systemctl 调用，短缓存显著降低开销。 */
    private static final long SNAPSHOT_TTL_MS = 30_000L;

    private static final int MAX_ECHO_CHARS = 4000;

    private static final String[] LEVEL_NAMES =
            {"emerg", "alert", "crit", "err", "warning", "notice", "info", "debug"};

    private final HostChannelService hostChannel;

    private final ObjectMapper objectMapper;

    /** 服务列表快照（volatile：读多写少，失效即整块替换） */
    private volatile Snapshot snapshot;

    // ------------------------------------------------------------------ 列表类

    /** 分页列表 */
    public PageResult<ServiceVO> page(String keyword, String activeState, String unitFileState,
                                      Boolean failedOnly, Boolean includeAlias,
                                      long pageNum, long pageSize) {
        List<ServiceVO> all = filtered(keyword, activeState, unitFileState, failedOnly, includeAlias);
        long total = all.size();
        long num = Math.max(1, pageNum);
        long size = Math.min(Math.max(1, pageSize), 500);
        int from = (int) Math.min((num - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        List<ServiceVO> records = from >= to ? List.of() : new ArrayList<>(all.subList(from, to));
        return PageResult.of(records, total, num, size);
    }

    /** 全量列表（兼容旧接口，已弃用：请用 {@link #page}） */
    @Deprecated
    public List<ServiceVO> list(String keyword) {
        return filtered(keyword, null, null, null, Boolean.TRUE);
    }

    /** 失败单元（本机当前应返回 certbot.service） */
    public List<ServiceVO> failed() {
        return filtered(null, "failed", null, Boolean.TRUE, Boolean.TRUE);
    }

    /** 总览统计 */
    public ServiceSummaryVO summary() {
        Snapshot current = snapshot();
        ServiceSummaryVO vo = new ServiceSummaryVO();
        vo.setHostChannelOk(current.hostChannelOk);
        vo.setSystemRunning(current.systemRunning);
        vo.setTotal(current.units.size());
        for (ServiceVO unit : current.units) {
            if (unit.isAlias()) {
                vo.setAlias(vo.getAlias() + 1);
            }
            if (unit.isFailed()) {
                vo.setFailed(vo.getFailed() + 1);
            } else if ("active".equals(unit.getActive())) {
                vo.setRunning(vo.getRunning() + 1);
            } else {
                vo.setStopped(vo.getStopped() + 1);
            }
            if (unit.isMasked()) {
                vo.setMasked(vo.getMasked() + 1);
            }
            String state = unit.getUnitFileState();
            if ("enabled".equals(state)) {
                vo.setEnabled(vo.getEnabled() + 1);
            } else if ("disabled".equals(state)) {
                vo.setDisabled(vo.getDisabled() + 1);
            }
        }
        return vo;
    }

    /** 保护清单（前端提前标识，避免用户点下去才被拦） */
    public Set<String> protectedUnits() {
        return ProtectedUnits.all();
    }

    // ------------------------------------------------------------------ 详情类

    /** 服务详情：结构化属性 + status/cat 原文 + 依赖关系 */
    public ServiceDetailVO detail(String name) {
        validateName(name);
        ServiceVO basic = findInSnapshot(name);
        if (basic == null) {
            basic = new ServiceVO();
            basic.setName(name);
            basic.setProtectedService(ProtectedUnits.isProtected(name));
        }

        Map<String, String> props = showOne(name, DETAIL_PROPERTIES);
        ServiceDetailVO vo = new ServiceDetailVO();
        vo.setBasic(basic);
        vo.setMainPid(positiveInt(props.get("MainPID")));
        vo.setMemoryBytes(parseMemory(props.get("MemoryCurrent")));
        vo.setCpuNanos(positiveLong(props.get("CPUUsageNSec")));
        vo.setRestartCount(intOrNull(props.get("NRestarts")));
        vo.setFragmentPath(blankToNull(props.get("FragmentPath")));
        vo.setActiveEnterTimestamp(blankToNull(props.get("ActiveEnterTimestamp")));
        vo.setUptimeSeconds(computeUptimeSeconds(props.get("ActiveEnterTimestampMonotonic")));
        vo.setExecStart(abbreviate(blankToNull(props.get("ExecStart")), 1200));
        vo.setNames(splitSpace(props.get("Names")));
        vo.setWantedBy(splitSpace(props.get("WantedBy")));
        vo.setRequiredBy(splitSpace(props.get("RequiredBy")));
        vo.setRequires(splitSpace(props.get("Requires")));
        vo.setAfter(splitSpace(props.get("After")));
        vo.setDependencies(listDependencies(name, false));
        vo.setDependents(listDependencies(name, true));
        vo.setRawStatus(abbreviate(hostChannel.callTextLenient("service.status",
                Map.of("name", name), "读取服务状态原文"), 20_000));
        vo.setUnitFile(abbreviate(hostChannel.callTextLenient("service.cat",
                Map.of("name", name), "读取 Unit 文件"), 20_000));
        vo.setProtectedService(ProtectedUnits.isProtected(name));
        if (vo.isProtectedService()) {
            int dependents = vo.getDependents().size();
            vo.setProtectionHint("该服务在保护清单内（可能承载面板自身访问路径）；"
                    + "当前有 " + dependents + " 个单元依赖它，停止/重启属于高危操作。");
        }
        return vo;
    }

    /** 服务日志（journalctl -o json，结构化解析后可按关键字与级别过滤） */
    public List<SysLogLine> logs(String name, int lines, String since, String until,
                                 String keyword, Integer minLevel) {
        validateName(name);
        int limit = Math.min(Math.max(1, lines), MAX_LOG_LINES);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("lines", limit);
        args.put("format", "json");
        if (since != null && !since.isBlank()) {
            args.put("since", since.trim());
        }
        if (until != null && !until.isBlank()) {
            args.put("until", until.trim());
        }
        HostResult result = hostChannel.call("service.logs", args, "读取服务日志", 60);
        List<SysLogLine> linesOut = new ArrayList<>();
        for (String raw : result.text().split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            SysLogLine line = parseJournalLine(raw);
            if (line == null) {
                continue;
            }
            if (minLevel != null && line.getLevel() != null && line.getLevel() > minLevel) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()
                    && (line.getMessage() == null
                        || !line.getMessage().toLowerCase(Locale.ROOT)
                                .contains(keyword.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            linesOut.add(line);
        }
        return linesOut;
    }

    // ------------------------------------------------------------------ 动作类

    /** 单个服务操作 */
    public ServiceActionResultVO action(String name, ServiceActionBody body) {
        validateName(name);
        String action = normalizeAction(body == null ? null : body.getAction());
        boolean protectedUnit = ProtectedUnits.isProtected(name);
        boolean danger = isDangerAction(action, protectedUnit);

        if (danger) {
            StpUtil.checkPermission("ops:service:danger");
            if (!name.equals(trimToNull(body.getConfirm()))) {
                return confirmRequired(name, action, protectedUnit);
            }
        }

        Map<String, Object> options = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(body.getNow())) {
            options.put("now", true);
        }
        if ("kill".equals(action)) {
            String signal = trimToNull(body.getSignal());
            signal = signal == null ? "SIGTERM" : signal.toUpperCase(Locale.ROOT);
            if (!SIGNALS.contains(signal)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "不支持的信号: " + signal);
            }
            options.put("signal", signal);
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("action", action);
        if (!options.isEmpty()) {
            args.put("options", options);
        }

        HostResult result = hostChannel.call("service.action", args, "执行服务操作", 120);
        if (RELOAD_AFTER.contains(action) && result.getExitCode() == 0) {
            try {
                hostChannel.call("service.daemonReload", Map.of(), "重载 systemd 配置", 60);
            } catch (RuntimeException e) {
                log.warn("daemon-reload 失败（不影响本次动作结果）: {}", e.getMessage());
            }
        }
        invalidate();
        return buildResult(name, action, result);
    }

    /** 批量服务操作：逐个执行，不因单个失败而中止 */
    public ServiceBatchResultVO batch(ServiceBatchBody body) {
        String action = normalizeAction(body == null ? null : body.getAction());
        List<String> names = body == null || body.getNames() == null
                ? List.of() : body.getNames();
        if (names.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请至少选择一个服务");
        }

        boolean anyDanger = false;
        boolean anyProtected = false;
        for (String name : names) {
            validateName(name);
            boolean flag = ProtectedUnits.isProtected(name);
            anyProtected = anyProtected || flag;
            anyDanger = anyDanger || isDangerAction(action, flag);
        }

        ServiceBatchResultVO summary = new ServiceBatchResultVO();
        summary.setTotal(names.size());
        if (anyDanger) {
            StpUtil.checkPermission("ops:service:danger");
            if (!"CONFIRM".equals(trimToNull(body.getConfirm()))) {
                summary.setConfirmRequired(true);
                summary.setConfirmKeyword("CONFIRM");
                summary.setFailed(names.size());
                for (String name : names) {
                    ServiceBatchResultVO.Item item = new ServiceBatchResultVO.Item();
                    item.setName(name);
                    item.setOk(false);
                    item.setMessage("批量" + action + "属于高危操作"
                            + (anyProtected ? "（批量中含保护清单服务）" : "") + "，需二次确认");
                    summary.getItems().add(item);
                }
                return summary;
            }
        }

        ServiceActionBody single = new ServiceActionBody();
        single.setAction(action);
        single.setNow(body.getNow());
        single.setConfirm(names.size() == 1 ? names.get(0) : "CONFIRM");
        int success = 0;
        for (String name : names) {
            ServiceBatchResultVO.Item item = new ServiceBatchResultVO.Item();
            item.setName(name);
            try {
                ServiceActionResultVO one = action(name, single);
                item.setOk(one.isOk());
                item.setMessage(one.getMessage());
                item.setActiveAfter(one.getActiveAfter());
            } catch (RuntimeException e) {
                item.setOk(false);
                item.setMessage(e.getMessage());
            }
            if (item.isOk()) {
                success++;
            }
            summary.getItems().add(item);
        }
        summary.setSuccess(success);
        summary.setFailed(names.size() - success);
        return summary;
    }

    /** 全局 daemon-reload */
    public void daemonReload() {
        hostChannel.call("service.daemonReload", Map.of(), "重载 systemd 配置", 60);
        invalidate();
    }

    /** 强制刷新快照（页面「刷新」按钮） */
    public void refresh() {
        invalidate();
        snapshot();
    }

    // ------------------------------------------------------------------ 内部实现

    private ServiceActionResultVO confirmRequired(String name, String action, boolean protectedUnit) {
        ServiceActionResultVO vo = new ServiceActionResultVO();
        vo.setName(name);
        vo.setAction(action);
        vo.setOk(false);
        vo.setConfirmRequired(true);
        vo.setConfirmKeyword(name);
        vo.setMessage("该操作属于高危操作（" + (protectedUnit
                ? "命中保护清单：" + name + " 可能承载面板/SSH 访问路径"
                : "mask/unmask 会改变单元的可启动性") + "），"
                + "请输入服务名 " + name + " 以确认");
        return vo;
    }

    private ServiceActionResultVO buildResult(String name, String action, HostResult result) {
        ServiceActionResultVO vo = new ServiceActionResultVO();
        vo.setName(name);
        vo.setAction(action);
        vo.setExitCode(result.getExitCode());
        vo.setStdout(abbreviate(result.text(), MAX_ECHO_CHARS));
        vo.setStderr(abbreviate(result.getStderr(), MAX_ECHO_CHARS));
        boolean ok = result.getExitCode() == 0 && !result.isTimedOut();
        vo.setOk(ok);

        try {
            HostResult probe = hostChannel.call("service.isActive", Map.of("name", name),
                    "回读服务状态", 30);
            vo.setActiveAfter(blankToNull(probe.dataString("isActive")));
            vo.setEnabledAfter(blankToNull(probe.dataString("isEnabled")));
            vo.setFailedAfter("failed".equals(vo.getActiveAfter()));
        } catch (RuntimeException e) {
            log.warn("回读服务状态失败: {} {}", name, e.getMessage());
        }

        if (ok) {
            vo.setMessage("已执行 " + action + "；当前状态 " + safe(vo.getActiveAfter(), "未知")
                    + "，自启 " + safe(vo.getEnabledAfter(), "未知"));
        } else if (result.isTimedOut()) {
            vo.setMessage("执行超时（systemd 未在限定时间内返回）");
        } else {
            vo.setMessage(abbreviate(result.errorText(), 500));
        }
        return vo;
    }

    /** 危险动作判定 */
    private boolean isDangerAction(String action, boolean protectedUnit) {
        if ("mask".equals(action) || "unmask".equals(action)) {
            return true;
        }
        return protectedUnit && !SAFE_ACTIONS.contains(action);
    }

    private String normalizeAction(String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "不支持的操作: " + action);
        }
        return normalized;
    }

    private void validateName(String name) {
        if (name == null || !UNIT_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "非法的服务名");
        }
    }

    private List<ServiceVO> filtered(String keyword, String activeState, String unitFileState,
                                     Boolean failedOnly, Boolean includeAlias) {
        Snapshot current = snapshot();
        String kw = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        boolean wantAlias = Boolean.TRUE.equals(includeAlias);
        boolean onlyFailed = Boolean.TRUE.equals(failedOnly);
        String wantActive = blankToNull(activeState);
        String wantState = blankToNull(unitFileState);

        List<ServiceVO> result = new ArrayList<>();
        for (ServiceVO unit : current.units) {
            if (unit.isAlias() && !wantAlias) {
                continue;
            }
            if (onlyFailed && !unit.isFailed()) {
                continue;
            }
            if (wantActive != null && !wantActive.equalsIgnoreCase(unit.getActive())) {
                continue;
            }
            if (wantState != null && !wantState.equalsIgnoreCase(unit.getUnitFileState())) {
                continue;
            }
            if (kw != null && !kw.isEmpty()
                    && !unit.getName().toLowerCase(Locale.ROOT).contains(kw)
                    && (unit.getDescription() == null
                        || !unit.getDescription().toLowerCase(Locale.ROOT).contains(kw))) {
                continue;
            }
            result.add(unit);
        }
        return result;
    }

    private ServiceVO findInSnapshot(String name) {
        for (ServiceVO unit : snapshot().units) {
            if (unit.getName().equals(name)) {
                return unit;
            }
        }
        return null;
    }

    private void invalidate() {
        snapshot = null;
    }

    /** 取快照（带 TTL 与双重检查） */
    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current != null && System.currentTimeMillis() - current.loadedAt < SNAPSHOT_TTL_MS) {
            return current;
        }
        synchronized (this) {
            Snapshot again = snapshot;
            if (again != null && System.currentTimeMillis() - again.loadedAt < SNAPSHOT_TTL_MS) {
                return again;
            }
            Snapshot fresh = load();
            snapshot = fresh;
            return fresh;
        }
    }

    /** 组装快照：list-unit-files ∪ list-units ∪ failed ∪ show */
    private Snapshot load() {
        HostCapability capability = hostChannel.capability();
        if (!capability.isOk()) {
            Snapshot empty = new Snapshot(List.of(), Set.of(),
                    System.currentTimeMillis(), false, capability.getSystemRunning());
            log.warn("宿主通道不可用，服务列表降级为空：{}", capability.getMessage());
            return empty;
        }

        Set<String> failed = new LinkedHashSet<>();
        for (String line : safeText("service.failed", "读取失败服务").split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 1 && parts[0].endsWith(".service")) {
                failed.add(parts[0]);
            }
        }

        Map<String, String> unitFiles = parseUnitFileStates(
                safeText("service.listUnitFiles", "读取服务单元清单"));
        Map<String, String[]> runtime = parseUnits(
                safeText("service.listUnits", "读取服务运行态"));

        Set<String> names = new LinkedHashSet<>(unitFiles.keySet());
        names.addAll(runtime.keySet());
        List<String> ordered = new ArrayList<>(names);
        Collections.sort(ordered);

        Map<String, Map<String, String>> realtime = showMany(ordered);
        double hostUptime = readHostUptimeSeconds();

        List<ServiceVO> units = new ArrayList<>(ordered.size());
        for (String name : ordered) {
            ServiceVO vo = new ServiceVO();
            vo.setName(name);
            String[] runtimeRow = runtime.get(name);
            String state = unitFiles.get(name);
            if (state == null) {
                state = "generated";
            }
            vo.setUnitFileState(state);
            vo.setMasked("masked".equals(state));
            vo.setAlias("alias".equals(state));
            vo.setLoad(runtimeRow == null ? "not-found" : runtimeRow[0]);
            vo.setActive(runtimeRow == null ? "inactive" : runtimeRow[1]);
            vo.setSub(runtimeRow == null ? "dead" : runtimeRow[2]);
            vo.setDescription(runtimeRow == null ? null : runtimeRow[3]);
            vo.setFailed(failed.contains(name));
            vo.setProtectedService(ProtectedUnits.isProtected(name));

            Map<String, String> props = realtime.get(name);
            if (props != null) {
                if (blankToNull(vo.getDescription()) == null) {
                    vo.setDescription(blankToNull(props.get("Description")));
                }
                vo.setMainPid(positiveInt(props.get("MainPID")));
                vo.setMemoryBytes(parseMemory(props.get("MemoryCurrent")));
                vo.setRestartCount(intOrNull(props.get("NRestarts")));
                vo.setFragmentPath(blankToNull(props.get("FragmentPath")));
                vo.setStateChangeTimestamp(blankToNull(props.get("StateChangeTimestamp")));
                vo.setUptimeSeconds(computeUptimeSeconds(props.get("ActiveEnterTimestampMonotonic")));
                if (vo.isAlias()) {
                    vo.setAliasOf(findCanonical(props.get("Names"), name));
                }
                if (vo.isFailed()) {
                    Long since = computeEpochMillis(props.get("StateChangeTimestampMonotonic"));
                    vo.setFailedSinceEpoch(since);
                    if (since != null) {
                        vo.setFailedSeconds(Math.max(0, (System.currentTimeMillis() - since) / 1000));
                    }
                }
            }
            if (hostUptime <= 0) {
                vo.setUptimeSeconds(null);
            }
            units.add(vo);
        }
        return new Snapshot(units, failed, System.currentTimeMillis(), true,
                capability.getSystemRunning());
    }

    private String safeText(String op, String label) {
        try {
            return hostChannel.callText(op, Map.of(), label, 60);
        } catch (RuntimeException e) {
            log.warn("{} 失败：{}", label, e.getMessage());
            return "";
        }
    }

    private Map<String, String> parseUnitFileStates(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].endsWith(".service")) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }

    private Map<String, String[]> parseUnits(String text) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 4 || !parts[0].endsWith(".service")) {
                continue;
            }
            StringBuilder desc = new StringBuilder();
            for (int i = 4; i < parts.length; i++) {
                if (desc.length() > 0) {
                    desc.append(' ');
                }
                desc.append(parts[i]);
            }
            map.put(parts[0], new String[]{parts[1], parts[2], parts[3], desc.toString()});
        }
        return map;
    }

    /**
     * 模板单元：`@` 后没有实例名（如 {@code autovt@.service}、{@code getty@.service}）。
     *
     * <p>它在 {@code systemctl list-unit-files} 里是合法条目（本机 226 个 .service 中有 22 个），
     * 却<b>不能</b>被 {@code systemctl show} 查询。模板单元本就没有运行时状态，
     * 故不参与批量查询，只保留清单条目本身。
     */
    private static final Pattern TEMPLATE_UNIT = Pattern.compile("^[^@]+@\\.service$");

    /** 批量取实时字段：分片调用 systemctl show，块间以空行分隔，用 Id 字段对齐 unit */
    private Map<String, Map<String, String>> showMany(List<String> names) {
        List<String> targets = new ArrayList<>(names.size());
        for (String name : names) {
            if (!TEMPLATE_UNIT.matcher(name).matches()) {
                targets.add(name);
            }
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (int i = 0; i < targets.size(); i += SHOW_CHUNK_SIZE) {
            showChunk(new ArrayList<>(
                    targets.subList(i, Math.min(i + SHOW_CHUNK_SIZE, targets.size()))), result);
        }
        return result;
    }

    /**
     * 取一批 unit 的实时属性；<b>整批失败时二分下探到单个 unit</b>。
     *
     * <p>为什么必须二分：{@code systemctl show} 只要遇到一个它拒绝的 unit 名就整体退出
     * （exitCode != 0）并丢弃剩余输出，一次坏名会连带丢掉同批几十个单元的数据。
     * 前置过滤已挡掉已知的模板单元，但 systemd 将来仍可能因其它原因拒绝（临时故障、
     * 权限变化、新增的异形单元名）。二分重试把影响面从「整片」压缩到「单个单元」，
     * 代价仅为 k·log₂(n) 次额外调用（k = 坏名个数），每次调用耗时毫秒级。
     */
    private void showChunk(List<String> chunk, Map<String, Map<String, String>> result) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("units", chunk);
        args.put("properties", LIST_PROPERTIES);
        HostResult study;
        try {
            study = hostChannel.call("service.show", args, "读取服务实时状态", 90);
        } catch (RuntimeException e) {
            log.warn("批量读取服务状态失败（{} 个单元）：{}", chunk.size(), e.getMessage());
            return;
        }
        if (study.getExitCode() != 0 && chunk.size() > 1) {
            int mid = chunk.size() / 2;
            showChunk(new ArrayList<>(chunk.subList(0, mid)), result);
            showChunk(new ArrayList<>(chunk.subList(mid, chunk.size())), result);
            return;
        }
        Map<String, String> block = new LinkedHashMap<>();
        for (String line : study.text().split("\n")) {
            if (line.isBlank()) {
                flushBlock(result, block);
                block = new LinkedHashMap<>();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                block.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        flushBlock(result, block);
    }

    private void flushBlock(Map<String, Map<String, String>> target, Map<String, String> block) {
        if (block.isEmpty()) {
            return;
        }
        String id = block.get("Id");
        if (id == null || id.isBlank()) {
            return;
        }
        target.put(id.trim(), block);
    }

    private Map<String, String> showOne(String name, List<String> properties) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("units", List.of(name));
        args.put("properties", properties);
        try {
            HostResult result = hostChannel.call("service.show", args, "读取服务属性", 60);
            Map<String, String> block = new LinkedHashMap<>();
            for (String line : result.text().split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    block.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return block;
        } catch (RuntimeException e) {
            log.warn("读取服务属性失败: {} {}", name, e.getMessage());
            return Map.of();
        }
    }

    /** list-dependencies 解析（--plain 去掉树形字符，每行一个单元名） */
    private List<String> listDependencies(String name, boolean reverse) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        if (reverse) {
            args.put("reverse", true);
        }
        try {
            String text = hostChannel.callText("service.dependencies", args, "读取服务依赖", 45);
            List<String> list = new ArrayList<>();
            for (String line : text.split("\n")) {
                String unit = line.trim();
                if (unit.isEmpty() || unit.equals(name) || !unit.endsWith(".service")) {
                    continue;
                }
                if (!list.contains(unit)) {
                    list.add(unit);
                }
            }
            return list;
        } catch (RuntimeException e) {
            log.debug("读取服务依赖失败: {} {}", name, e.getMessage());
            return List.of();
        }
    }

    /** 解析 journalctl -o json 单行 */
    @SuppressWarnings("unchecked")
    private SysLogLine parseJournalLine(String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(raw, Map.class);
            SysLogLine line = new SysLogLine();
            Long micros = longOrNull(map.get("__REALTIME_TIMESTAMP"));
            if (micros != null) {
                long millis = micros / 1000;
                line.setTimestamp(millis);
                line.setTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
                        ZoneId.systemDefault()).toString());
            }
            Integer level = intOrNull(map.get("PRIORITY"));
            line.setLevel(level);
            if (level != null && level >= 0 && level < LEVEL_NAMES.length) {
                line.setLevelName(LEVEL_NAMES[level]);
            }
            line.setPid(intOrNull(map.get("_PID")));
            line.setIdentifier(firstNonBlank(str(map.get("SYSLOG_IDENTIFIER")),
                    str(map.get("_COMM"))));
            line.setUnit(str(map.get("_SYSTEMD_UNIT")));
            line.setMessage(messageOf(map.get("MESSAGE")));
            return line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** journal 的 MESSAGE 可能是字符串，也可能是非 UTF-8 字节数组 */
    private String messageOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            return text;
        }
        if (raw instanceof List<?> list) {
            byte[] bytes = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                bytes[i] = item instanceof Number number ? number.byteValue() : 0;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(raw);
    }

    /** 读宿主机 uptime（容器 pid: host，故 /proc 即宿主机 /proc） */
    private double readHostUptimeSeconds() {
        try {
            String text = Files.readString(Path.of("/proc/uptime"), StandardCharsets.UTF_8).trim();
            return Double.parseDouble(text.split("\\s+")[0]);
        } catch (Exception e) {
            return 0d;
        }
    }

    /**
     * 由 systemd 的单调时钟属性反推「距今秒数」。
     *
     * <p>为什么不用 systemd 输出的时间串：它是本地化格式且带 CST 这类有歧义的时区缩写，
     * Java 无法可靠解析；单调时钟只依赖宿主 uptime，绝对无歧义。
     */
    private Long computeUptimeSeconds(String monotonicMicros) {
        Long micros = longOrNull(monotonicMicros);
        double hostUptime = readHostUptimeSeconds();
        if (micros == null || micros <= 0 || hostUptime <= 0) {
            return null;
        }
        long seconds = Math.round(hostUptime - micros / 1_000_000d);
        return seconds >= 0 ? seconds : null;
    }

    private Long computeEpochMillis(String monotonicMicros) {
        Long micros = longOrNull(monotonicMicros);
        double hostUptime = readHostUptimeSeconds();
        if (micros == null || micros <= 0 || hostUptime <= 0) {
            return null;
        }
        long agoMillis = Math.max(0, Math.round((hostUptime - micros / 1_000_000d) * 1000));
        return System.currentTimeMillis() - agoMillis;
    }

    private String findCanonical(String names, String self) {
        for (String item : splitSpace(names)) {
            if (!item.equals(self)) {
                return item;
            }
        }
        return null;
    }

    private List<String> splitSpace(String value) {
        String text = blankToNull(value);
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("\\s+"))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static Long parseMemory(String raw) {
        String text = blankToNull(raw);
        if (text == null || text.startsWith("[") || "infinity".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer positiveInt(String raw) {
        Integer value = intOrNull(raw);
        return value == null || value <= 0 ? null : value;
    }

    private static Long positiveLong(String raw) {
        Long value = longOrNull(raw);
        return value == null || value <= 0 ? null : value;
    }

    private static Integer intOrNull(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = blankToNull(raw == null ? null : String.valueOf(raw));
        if (text == null || text.startsWith("[")) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = blankToNull(raw == null ? null : String.valueOf(raw));
        if (text == null || text.startsWith("[") || "infinity".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n...(已截断)";
    }

    /** 服务列表快照 */
    private record Snapshot(List<ServiceVO> units, Set<String> failedNames, long loadedAt,
                            boolean hostChannelOk, String systemRunning) {}
}
