package com.serverpanel.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.ops.dto.FirewallActionResultVO;
import com.serverpanel.ops.dto.FirewallDefaultPolicyBody;
import com.serverpanel.ops.dto.FirewallGuard;
import com.serverpanel.ops.dto.FirewallRule;
import com.serverpanel.ops.dto.FirewallRuleBody;
import com.serverpanel.ops.dto.FirewallStatus;
import com.serverpanel.ops.entity.OpsFirewallChange;
import com.serverpanel.ops.mapper.OpsFirewallChangeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 防火墙管理（ufw）。
 *
 * <p>所有命令经<b>宿主执行通道</b>执行：面板容器里没有 ufw，这正是重构前页面顶部
 * 一直飘着「未检测到可用的防火墙」而宿主机 ufw 明明开着并管着 55 条规则的原因。
 *
 * <p>三条设计原则：
 * <ol>
 *   <li><b>解析对齐真实输出</b>：ufw 的动作是 {@code ALLOW IN} / {@code REJECT IN}
 *       这类<u>两个词</u>的形式，旧正则按 {@code \S+} 取动作，把 {@code IN} 吃进了来源列。</li>
 *   <li><b>删除按编号但验指纹</b>：规则编号会在每次增删后重排，仅凭编号删除可能误删
 *       另一条规则，故删除前重新拉取并校验 {@code to|action|from} 指纹。</li>
 *   <li><b>每次变更留快照与回滚脚本</b>：防火墙误操作代价极高（可能把自己锁在门外），
 *       必须能精确看到「哪条规则变了」并能按差集重放撤销；全局开关类变更默认挂看门狗，
 *       超时未确认则自动回滚。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirewallService {

    /** 面板写入规则时统一加的备注前缀，用于识别「这条规则是面板建的」 */
    public static final String PANEL_COMMENT_PREFIX = "psm:";

    /** 允许的写动作 */
    public static final Set<String> ACTIONS = Set.of("allow", "deny", "reject", "limit");

    /** 允许的默认策略 */
    public static final Set<String> POLICIES = Set.of("allow", "deny", "reject");

    /** 允许的目标类型 */
    public static final Set<String> KINDS = Set.of("port", "range", "multi", "any");

    /**
     * ufw status numbered 规则行。
     *
     * <p>为什么必须显式枚举动作词与方向词：动作与方向之间是<u>单个空格</u>
     * （如 {@code REJECT IN}），而列与列之间是<u>多个空格</u>。旧正则用
     * {@code (\S+)} 取动作，拿到 {@code REJECT} 后剩下的 {@code IN 172.238.101.222}
     * 整个被当成来源——本机 55 条规则里的 Fail2Ban 规则全是 REJECT IN，
     * 于是「来源」字段被污染成 {@code IN 172.238.101.222}。
     */
    private static final Pattern UFW_RULE = Pattern.compile(
            "^\\[\\s*(?<no>\\d+)\\]\\s+(?<to>\\S+(?:\\s+\\(v6\\))?(?:\\s+on\\s+\\S+)?)\\s{2,}"
                    + "(?<action>ALLOW|DENY|REJECT|LIMIT)(?:\\s+(?<dir>IN|OUT|FWD))?\\s+"
                    + "(?<from>\\S+(?:\\s+\\(v6\\))?)(?:\\s+#\\s*(?<comment>.*))?\\s*$");

    /** verbose 头部的默认策略：`Default: deny (incoming), allow (outgoing), deny (routed)` */
    private static final Pattern DEFAULT_POLICY =
            Pattern.compile("(?<policy>allow|deny|reject)\\s*\\((?<dir>incoming|outgoing|routed)\\)",
                    Pattern.CASE_INSENSITIVE);

    /** 端口规格：22 / 22/tcp / 40000:40100/tcp / 80,443/tcp */
    private static final Pattern TO_PORT = Pattern.compile("^(?<num>\\d+)(?::(?<end>\\d+))?(?:/(?<proto>tcp|udp))?$");
    private static final Pattern TO_MULTI = Pattern.compile("^(?<nums>[\\d,]+)/(?<proto>tcp|udp)$");

    private final HostChannelService hostChannel;

    private final OpsFirewallChangeMapper changeMapper;

    private final ObjectMapper objectMapper;

    /** 面板端口（后端 + 前端），用于生存线判断 */
    @Value("${serverpanel.ops.firewall.panel-ports:8080,3000}")
    private String panelPortsConfig;

    /** 看门狗窗口（秒）；全局开关类变更后到期未确认则自动回滚 */
    @Value("${serverpanel.ops.firewall.watchdog-seconds:300}")
    private int watchdogSeconds;

    // ==================== 状态 ====================

    /**
     * 读取防火墙整体状态。
     *
     * @param clientIp 发起请求的客户端 IP，用于生存线提示
     */
    public FirewallStatus status(String clientIp) {
        FirewallStatus st = new FirewallStatus();
        HostCapability cap = hostChannel.capability();
        st.setHostChannel(cap);
        String backend = cap.isOk() && cap.getFirewallBackend() != null
                ? cap.getFirewallBackend() : "none";
        st.setBackend(backend);
        st.setAvailable(cap.isOk() && !"none".equals(backend));
        st.setRules(List.of());
        st.setRuleCount(0);
        if (!cap.isOk()) {
            st.setMessage(cap.getMessage());
            return st;
        }
        try {
            HostResult raw = hostChannel.call("firewall.statusRaw", Map.of(), "读取防火墙状态", 30);
            String numbered = raw.dataString("numbered");
            applyVerbose(raw.text(), st);
            List<FirewallRule> rules = parseRules(numbered);
            st.setRules(rules);
            st.setRuleCount(rules.size());
            st.setIpv6(rules.stream().anyMatch(FirewallRule::isIpv6));
            st.setVersion(version());
            st.setGuard(buildGuard(rules, clientIp));
        } catch (RuntimeException e) {
            st.setAvailable(false);
            st.setMessage("读取防火墙状态失败：" + e.getMessage());
            log.warn("读取防火墙状态失败: {}", e.getMessage());
        }
        return st;
    }

    /** ufw show raw 原文（排障用） */
    public String raw() {
        return hostChannel.callText("firewall.raw", Map.of(), "读取防火墙原始规则", 30);
    }

    /** 生存线信息（SSH 端口、面板端口、风险提示） */
    public FirewallGuard guard(String clientIp) {
        return buildGuard(parseRules(numberedSnapshot()), clientIp);
    }

    private String version() {
        try {
            String text = hostChannel.callText("firewall.version", Map.of(), "读取防火墙版本", 15);
            String first = text.split("\\r?\\n")[0].trim();
            return first.length() > 40 ? first.substring(0, 40) : first;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void applyVerbose(String verbose, FirewallStatus st) {
        FirewallStatus.DefaultPolicy dp = new FirewallStatus.DefaultPolicy();
        st.setDefaultPolicy(dp);
        st.setActive(false);
        for (String line : verbose == null ? new String[0] : verbose.split("\\r?\\n")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "Status:", 0, 7)) {
                st.setActive(t.substring(7).trim().equalsIgnoreCase("active"));
            } else if (t.regionMatches(true, 0, "Logging:", 0, 8)) {
                st.setLogging(t.substring(8).trim());
            } else if (t.regionMatches(true, 0, "Default:", 0, 8)) {
                Matcher m = DEFAULT_POLICY.matcher(t.substring(8));
                while (m.find()) {
                    String policy = m.group("policy").toLowerCase(Locale.ROOT);
                    switch (m.group("dir").toLowerCase(Locale.ROOT)) {
                        case "incoming" -> dp.setIncoming(policy);
                        case "outgoing" -> dp.setOutgoing(policy);
                        case "routed" -> dp.setRouted(policy);
                        default -> {
                            // ufw 只输出这三个方向
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 ufw status numbered。
     *
     * <p>与旧实现的差异见类注释与 {@link #UFW_RULE} 的说明：动作与方向被正确拆分，
     * {@code (v6)} 后缀被剥离并转为 {@code ipv6} 标记，注释被单独提取。
     */
    public List<FirewallRule> parseRules(String numbered) {
        List<FirewallRule> rules = new ArrayList<>();
        if (numbered == null || numbered.isBlank()) {
            return rules;
        }
        for (String line : numbered.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = UFW_RULE.matcher(t);
            if (!m.matches()) {
                continue;
            }
            FirewallRule rule = new FirewallRule();
            rule.setNo(Integer.parseInt(m.group("no")));
            String to = m.group("to").trim();
            boolean ipv6 = to.endsWith("(v6)") || m.group("from").endsWith("(v6)");
            rule.setIpv6(ipv6);
            rule.setTo(stripV6(to));
            rule.setToKind(toKind(rule.getTo()));
            rule.setAction(m.group("action").toLowerCase(Locale.ROOT));
            String dir = m.group("dir");
            rule.setDirection(dir == null ? "in" : dir.toLowerCase(Locale.ROOT));
            rule.setFrom(stripV6(m.group("from").trim()));
            rule.setSourceKind(sourceKind(rule.getFrom()));
            String comment = m.group("comment");
            rule.setComment(comment == null ? "" : comment.trim());
            rule.setProvenance(provenance(rule.getComment()));
            rule.setDeletable(!"fail2ban".equals(rule.getProvenance())
                    && !("any".equals(rule.getToKind()) && "reject".equals(rule.getAction())));
            rule.setFingerprint(rule.getTo() + "|" + rule.getAction() + "|" + rule.getFrom());
            rules.add(rule);
        }
        return rules;
    }

    private static String stripV6(String value) {
        String v = value.replace("(v6)", "").trim();
        return v.isEmpty() ? "any" : v;
    }

    private static String toKind(String to) {
        if (to == null || to.isBlank() || "any".equalsIgnoreCase(to)
                || "anywhere".equalsIgnoreCase(to)) {
            return "any";
        }
        String portPart = to.contains(" on ") ? to.substring(0, to.indexOf(" on ")) : to;
        if (TO_MULTI.matcher(portPart).matches()) {
            return portPart.indexOf(',') > 0 ? "multi" : "port";
        }
        Matcher m = TO_PORT.matcher(portPart);
        if (!m.matches()) {
            // 应用名规则（ufw app profile）等非端口形态
            return "app";
        }
        return m.group("end") != null ? "range" : "port";
    }

    private static String sourceKind(String from) {
        if (from == null || from.isBlank() || "any".equalsIgnoreCase(from)
                || "anywhere".equalsIgnoreCase(from)) {
            return "any";
        }
        return from.contains("/") ? "cidr" : "ip";
    }

    private static String provenance(String comment) {
        if (comment == null || comment.isBlank()) {
            return "unknown";
        }
        String lower = comment.toLowerCase(Locale.ROOT);
        if (lower.contains("fail2ban")) {
            return "fail2ban";
        }
        if (comment.startsWith(PANEL_COMMENT_PREFIX)) {
            return "panel";
        }
        return "manual";
    }

    // ==================== 生存线 ====================

    private FirewallGuard buildGuard(List<FirewallRule> rules, String clientIp) {
        FirewallGuard g = new FirewallGuard();
        List<Integer> ssh = sshPorts();
        List<Integer> panel = panelPorts();
        g.setSshPorts(ssh);
        g.setPanelPorts(panel);
        g.setClientIp(clientIp);
        g.setForeignRuleCount((int) rules.stream()
                .filter(r -> "fail2ban".equals(r.getProvenance())).count());

        List<Integer> missingSsh = new ArrayList<>();
        for (int p : ssh) {
            if (!isPortAllowed(rules, p)) {
                missingSsh.add(p);
            }
        }
        List<Integer> missingPanel = new ArrayList<>();
        for (int p : panel) {
            if (!isPortAllowed(rules, p)) {
                missingPanel.add(p);
            }
        }
        g.setSshAllowed(missingSsh.isEmpty());
        List<String> warnings = new ArrayList<>();
        if (!missingSsh.isEmpty()) {
            warnings.add("SSH 端口 " + missingSsh + " 当前没有放行规则；收紧默认策略或停用防火墙前请先放行，否则可能永久失去远程登录能力");
        }
        if (!missingPanel.isEmpty()) {
            warnings.add("面板端口 " + missingPanel + " 当前没有放行规则；一旦默认入站策略收紧，本面板将无法访问");
        }
        g.setWarnings(warnings);
        return g;
    }

    /** 某个端口当前是否被显式放行（allow 且方向为 in） */
    private boolean isPortAllowed(List<FirewallRule> rules, int port) {
        for (FirewallRule r : rules) {
            if (!"allow".equals(r.getAction()) || !"in".equals(r.getDirection())) {
                continue;
            }
            if (coversTarget(r.getTo(), r.getToKind(), port)) {
                return true;
            }
        }
        return false;
    }

    private boolean coversTarget(String to, String kind, int port) {
        if ("any".equals(kind)) {
            return true;
        }
        String portPart = to.contains(" on ") ? to.substring(0, to.indexOf(" on ")) : to;
        if ("multi".equals(kind)) {
            Matcher m = TO_MULTI.matcher(portPart);
            if (!m.matches()) {
                return false;
            }
            for (String n : m.group("nums").split(",")) {
                if (Integer.parseInt(n) == port) {
                    return true;
                }
            }
            return false;
        }
        Matcher m = TO_PORT.matcher(portPart);
        if (!m.matches()) {
            return false;
        }
        int start = Integer.parseInt(m.group("num"));
        int end = m.group("end") == null ? start : Integer.parseInt(m.group("end"));
        return port >= start && port <= end;
    }

    private List<Integer> sshPorts() {
        List<Integer> ports = new ArrayList<>();
        try {
            HostResult r = hostChannel.call("host.sshdPorts", Map.of(), "读取 SSH 端口", 20);
            Object value = r.getData() == null ? null : r.getData().get("ports");
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Number n) {
                        ports.add(n.intValue());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取 SSH 端口失败: {}", e.getMessage());
        }
        if (ports.isEmpty()) {
            ports.add(22);
        }
        return ports;
    }

    private List<Integer> panelPorts() {
        List<Integer> ports = new ArrayList<>();
        for (String part : panelPortsConfig.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                try {
                    ports.add(Integer.parseInt(t));
                } catch (NumberFormatException ignored) {
                    // 配置项写错就忽略，不至于让整个状态接口失败
                }
            }
        }
        return ports;
    }

    // ==================== 写入 ====================

    /** 新增规则 */
    public FirewallActionResultVO addRule(FirewallRuleBody body) {
        validateWrite(body);
        requireGuard(body, null, body.getConfirm());
        Map<String, Object> spec = spec(body);
        int beforeCount = parseRules(numberedSnapshot()).size();
        String before = numberedSnapshot();
        HostResult r = hostChannel.call("firewall.addRule", spec, "新增防火墙规则", 60);
        String after = numberedSnapshot();
        List<String> diff = diffRules(parseRules(before), parseRules(after));
        List<Map<String, Object>> undo = List.of(
                opArgs("firewall.deleteRule", withoutComment(spec)));
        Long changeId = recordChange("ADD_RULE", describe(body), before, after, diff, undo,
                body.getConfirm() != null, null, r.getExitCode() == 0,
                r.getExitCode() == 0 ? null : r.errorText());
        return result(r, changeId, diff, null);
    }

    /**
     * 删除规则（按编号，但先校验指纹）。
     *
     * <p>为什么不能只用编号：ufw 的规则编号在每次增删后都会重排。若前端停在旧列表上，
     * 用户点「删除第 12 条」时，服务端看到的第 12 条可能已经不是他看到的那条。
     * 指纹校验把「删除」从「按位置」变成「按内容」，杜绝这类误删。
     */
    public FirewallActionResultVO deleteRule(FirewallRuleBody body) {
        if (body.getNo() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少规则编号");
        }
        String before = numberedSnapshot();
        List<FirewallRule> rules = parseRules(before);
        FirewallRule target = rules.stream()
                .filter(r -> r.getNo().equals(body.getNo())).findFirst().orElse(null);
        if (target == null) {
            throw new ServiceException(ErrorCode.FIREWALL_RULE_NOT_FOUND);
        }
        if (body.getFingerprint() != null && !body.getFingerprint().isBlank()
                && !body.getFingerprint().equals(target.getFingerprint())) {
            throw new ServiceException(ErrorCode.FIREWALL_RULE_CHANGED);
        }
        if (!target.isDeletable() && !body.isForce()) {
            throw new ServiceException(ErrorCode.FIREWALL_FOREIGN_RULE);
        }
        requireGuard(null, target, body.getConfirm());

        // IPv4/IPv6 成对规则：开启 IPv6 时一次 add 会写入本体与 (v6) 副本两条，
        // 只删其中一条会留下「看不见的半条规则」——放行规则还算温和，
        // 若是 deny/reject，使用者以为删干净了，v6 侧其实仍在拦。
        List<FirewallRule> batch = new ArrayList<>();
        batch.add(target);
        String fp = target.getFingerprint();
        if (fp != null && !fp.isBlank()) {
            for (FirewallRule r0 : rules) {
                if (r0.getNo().equals(target.getNo())) {
                    continue;
                }
                if (fp.equals(r0.getFingerprint()) && r0.isIpv6() != target.isIpv6()) {
                    batch.add(r0);
                    break;
                }
            }
        }
        // ufw 每删一条都会重排编号：必须先删编号大的，再删小的
        batch.sort((a, b) -> Integer.compare(b.getNo(), a.getNo()));

        boolean ok = true;
        List<String> diff = new ArrayList<>();
        List<Map<String, Object>> undo = new ArrayList<>();
        String lastCommand = null;
        for (FirewallRule one : batch) {
            HostResult r = hostChannel.call("firewall.deleteByNo",
                    Map.of("no", one.getNo()), "删除防火墙规则", 60);
            lastCommand = r.dataString("command");
            if (r.getExitCode() != 0) {
                ok = false;
                diff.add("! 删除 #" + one.getNo() + " 失败：" + r.errorText());
            } else if (batch.size() > 1) {
                diff.add("- #" + one.getNo() + " " + describe(one)
                        + (one.isIpv6() ? "（IPv6 副本）" : ""));
            }
            Map<String, Object> restore = specFromRule(one);
            if (restore != null) {
                undo.add(opArgs("firewall.addRule", restore));
            }
        }
        String after = numberedSnapshot();
        if (diff.isEmpty()) {
            diff.addAll(diffRules(parseRules(before), parseRules(after)));
        }
        Long changeId = recordChange("DELETE_RULE", describe(target), before, after, diff, undo,
                body.getConfirm() != null, null, ok,
                ok ? null : "部分规则删除失败");
        FirewallActionResultVO vo = new FirewallActionResultVO();
        vo.setOk(ok);
        vo.setMessage(ok
                ? (batch.size() > 1 ? "已删除规则及其 IPv6 副本" : "规则已删除")
                : "删除未完全成功，请查看差异");
        vo.setCommand(lastCommand);
        vo.setChangeId(changeId);
        vo.setDiff(diff);
        return vo;
    }

    /** 启用/停用防火墙（L3 + 看门狗） */
    public FirewallActionResultVO setEnabled(boolean enabled, String confirm) {
        if (enabled) {
            // 启用后默认策略立即生效：若默认入站为 deny 而 SSH 端口未放行，启用即失联
            FirewallStatus st = status(null);
            FirewallGuard g = st.getGuard();
            boolean denyIncoming = st.getDefaultPolicy() != null
                    && "deny".equalsIgnoreCase(str(st.getDefaultPolicy().getIncoming()));
            if (g != null && !g.isSshAllowed() && denyIncoming) {
                requireConfirm(confirm, "SSH " + g.getSshPorts().get(0),
                        "启用防火墙且默认入站策略为 deny，但 SSH 端口未放行，启用后将无法远程登录");
            }
        } else {
            requireConfirm(confirm, "DISABLE", "停用防火墙将让主机完全暴露，需二次确认");
        }
        String before = numberedSnapshot();
        HostResult r = hostChannel.call("firewall.setEnabled",
                Map.of("enabled", enabled), enabled ? "启用防火墙" : "停用防火墙", 90);
        String after = numberedSnapshot();
        List<String> diff = List.of(enabled ? "+ 防火墙已启用" : "- 防火墙已停用");
        List<Map<String, Object>> undo = List.of(
                opArgs("firewall.setEnabled", Map.of("enabled", !enabled)));
        Long changeId = recordChange(enabled ? "ENABLE" : "DISABLE",
                enabled ? "启用防火墙" : "停用防火墙", before, after, diff, undo,
                confirm != null, watchdogSeconds, r.getExitCode() == 0,
                r.getExitCode() == 0 ? null : r.errorText());
        return result(r, changeId, diff,
                armWatchdog(enabled ? "启用防火墙" : "停用防火墙", undo));
    }

    /** 设置默认策略（L3 + 看门狗） */
    public FirewallActionResultVO setDefault(FirewallDefaultPolicyBody body, String confirm) {
        Map<String, Object> args = new LinkedHashMap<>();
        FirewallStatus st = status(null);
        FirewallStatus.DefaultPolicy old = st.getDefaultPolicy();
        if (body.getIncoming() != null && POLICIES.contains(body.getIncoming().toLowerCase(Locale.ROOT))) {
            args.put("incoming", body.getIncoming().toLowerCase(Locale.ROOT));
        }
        if (body.getOutgoing() != null && POLICIES.contains(body.getOutgoing().toLowerCase(Locale.ROOT))) {
            args.put("outgoing", body.getOutgoing().toLowerCase(Locale.ROOT));
        }
        if (body.getRouted() != null && POLICIES.contains(body.getRouted().toLowerCase(Locale.ROOT))) {
            args.put("routed", body.getRouted().toLowerCase(Locale.ROOT));
        }
        if (args.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "至少要指定一个方向的默认策略");
        }
        boolean tightening = "deny".equals(args.get("incoming")) || "reject".equals(args.get("incoming"));
        FirewallGuard g = st.getGuard();
        if (tightening && g != null && !g.isSshAllowed()) {
            requireConfirm(confirm, "SSH " + g.getSshPorts().get(0),
                    "收紧默认入站策略但 SSH 端口未放行，变更后将无法远程登录");
        }
        String before = numberedSnapshot();
        HostResult r = hostChannel.call("firewall.setDefault", args, "设置防火墙默认策略", 60);
        String after = numberedSnapshot();

        Map<String, Object> undoArgs = new LinkedHashMap<>();
        if (old != null) {
            if (old.getIncoming() != null) {
                undoArgs.put("incoming", old.getIncoming());
            }
            if (old.getOutgoing() != null) {
                undoArgs.put("outgoing", old.getOutgoing());
            }
            if (old.getRouted() != null) {
                undoArgs.put("routed", old.getRouted());
            }
        }
        List<String> diff = new ArrayList<>();
        args.forEach((k, v) -> diff.add("* 默认" + k + "策略：" + v));
        List<Map<String, Object>> undo = undoArgs.isEmpty() ? List.of()
                : List.of(opArgs("firewall.setDefault", undoArgs));
        Long changeId = recordChange("SET_DEFAULT", "默认策略 " + args, before, after, diff, undo,
                confirm != null, watchdogSeconds, r.getExitCode() == 0,
                r.getExitCode() == 0 ? null : r.errorText());
        return result(r, changeId, diff, armWatchdog("设置默认策略 " + args, undo));
    }

    /** 重载防火墙 */
    public FirewallActionResultVO reload() {
        String before = numberedSnapshot();
        HostResult r = hostChannel.call("firewall.reload", Map.of(), "重载防火墙", 60);
        String after = numberedSnapshot();
        List<String> diff = diffRules(parseRules(before), parseRules(after));
        Long changeId = recordChange("RELOAD", "重载防火墙", before, after, diff, List.of(),
                false, null, r.getExitCode() == 0,
                r.getExitCode() == 0 ? null : r.errorText());
        return result(r, changeId, diff, null);
    }

    // ==================== 变更历史与回滚 ====================

    public PageResult<OpsFirewallChange> changes(int pageNum, int pageSize) {
        Page<OpsFirewallChange> page = changeMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsFirewallChange>()
                        .orderByDesc(OpsFirewallChange::getCreatedAt));
        // 列表不回传两份完整快照，避免一次请求拖回上百 KB
        for (OpsFirewallChange row : page.getRecords()) {
            row.setBeforeSnapshot(null);
            row.setAfterSnapshot(null);
        }
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    public OpsFirewallChange changeDetail(Long id) {
        OpsFirewallChange row = changeMapper.selectById(id);
        // 「查不到」是 404 语义：既不是调用方参数错，也不是服务端故障
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "变更记录不存在：" + id);
        }
        return row;
    }

    /**
     * 回滚某次变更：按 undo 脚本重放。
     *
     * <p>只回滚「不可重放就会出错」的操作：没有 undo 脚本或已经回滚过的变更直接拒绝，
     * 避免对着一份早就失效的快照做危险猜测。
     */
    public FirewallActionResultVO rollback(Long id, String confirm) {
        OpsFirewallChange row = changeMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "变更记录不存在：" + id);
        }
        if (row.getRollbackable() == null || row.getRollbackable() == 0
                || row.getUndoJson() == null || row.getUndoJson().isBlank()) {
            throw new ServiceException(ErrorCode.FIREWALL_ROLLBACK_UNAVAILABLE);
        }
        if (row.getRolledBack() != null && row.getRolledBack() == 1) {
            throw new ServiceException(ErrorCode.FIREWALL_ROLLBACK_UNAVAILABLE);
        }
        requireConfirm(confirm, "ROLLBACK", "回滚防火墙变更属于高危操作，需二次确认");

        List<Map<String, Object>> steps = new ArrayList<>();
        try {
            Object parsed = objectMapper.readValue(row.getUndoJson(), Object.class);
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        steps.add(castMap(map));
                    }
                }
            }
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.FIREWALL_ROLLBACK_UNAVAILABLE);
        }
        if (steps.isEmpty()) {
            throw new ServiceException(ErrorCode.FIREWALL_ROLLBACK_UNAVAILABLE);
        }
        String before = numberedSnapshot();
        boolean ok = true;
        List<String> diff = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String op = str(step.get("op"));
            Object args = step.get("args");
            Map<String, Object> callArgs = args instanceof Map<?, ?> map ? castMap(map) : Map.of();
            try {
                HostResult r = hostChannel.call(op, callArgs, "回滚防火墙变更", 60);
                if (r.getExitCode() != 0) {
                    ok = false;
                    diff.add("! " + op + " 失败：" + r.errorText());
                } else {
                    diff.add("* 已重放 " + op);
                }
            } catch (RuntimeException e) {
                ok = false;
                diff.add("! " + op + " 异常：" + e.getMessage());
            }
        }
        String after = numberedSnapshot();
        Long changeId = recordChange("ROLLBACK", "回滚变更 #" + id, before, after, diff, List.of(),
                true, null, ok, ok ? null : "部分步骤失败");
        if (ok) {
            OpsFirewallChange mark = new OpsFirewallChange();
            mark.setId(id);
            mark.setRolledBack(1);
            changeMapper.updateById(mark);
        }
        FirewallActionResultVO vo = new FirewallActionResultVO();
        vo.setOk(ok);
        vo.setMessage(ok ? "已回滚变更 #" + id : "回滚未完全成功，请查看差异");
        vo.setChangeId(changeId);
        vo.setDiff(diff);
        return vo;
    }

    // ==================== 看门狗 ====================

    public FirewallActionResultVO.WatchdogBrief watchdog() {
        HostResult r = hostChannel.call("watchdog.status", Map.of("id", watchdogId()),
                "查询防火墙看门狗", 20);
        Map<String, Object> data = r.getData() == null ? Map.of() : r.getData();
        if (!Boolean.TRUE.equals(data.get("armed"))) {
            return null;
        }
        FirewallActionResultVO.WatchdogBrief w = new FirewallActionResultVO.WatchdogBrief();
        w.setId(watchdogId());
        Object left = data.get("secondsLeft");
        w.setSecondsLeft(left instanceof Number n ? n.intValue() : 0);
        w.setExpiresAt(str(data.get("expiresAt")));
        w.setReason(str(data.get("reason")));
        return w;
    }

    /** 保留变更（撤销看门狗） */
    public void confirmWatchdog() {
        hostChannel.call("watchdog.confirm", Map.of("id", watchdogId()), "保留防火墙变更", 30);
    }

    /** 看门狗以「每次全局变更一个固定 ID」的方式管理，简化前端倒计时展示 */
    private String watchdogId() {
        return "fw-global";
    }

    private FirewallActionResultVO.WatchdogBrief armWatchdog(String reason,
                                                             List<Map<String, Object>> undo) {
        return armWatchdog(reason, undo, watchdogSeconds);
    }

    private FirewallActionResultVO.WatchdogBrief armWatchdog(String reason,
                                                             List<Map<String, Object>> undo,
                                                             int seconds) {
        if (undo == null || undo.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", watchdogId());
            args.put("seconds", Math.max(30, Math.min(3600, seconds)));
            args.put("reason", reason);
            args.put("undo", undo);
            hostChannel.call("watchdog.arm", args, "注册防火墙看门狗", 30);
            return watchdog();
        } catch (RuntimeException e) {
            log.warn("注册防火墙看门狗失败（变更已生效，但不会自动回滚）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 手动注册看门狗。
     *
     * <p>仅支持 {@code action=disable}：到期停用防火墙以恢复访问。
     * 为什么不支持「恢复快照」：那需要一个能把整套规则重放的代理 op，
     * 在没有先行变更的前提下凭空构造它既复杂又危险（重放失败会让规则集处于半应用状态）。
     * 真正需要「恢复原状」的场景走全局开关类接口——它们会自动携带完整的 undo 脚本注册看门狗。
     */
    public FirewallActionResultVO.WatchdogBrief armWatchdogManual(Integer seconds, String action) {
        if (action != null && !action.isBlank() && !"disable".equalsIgnoreCase(action)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "手动看门狗仅支持 action=disable（到期停用防火墙以恢复访问）");
        }
        int sec = seconds == null || seconds <= 0 ? watchdogSeconds : seconds;
        List<Map<String, Object>> undo = List.of(
                opArgs("firewall.setEnabled", Map.of("enabled", false)));
        return armWatchdog("手动看门狗（到期停用防火墙）", undo, sec);
    }

    // ==================== 内部：校验与护栏 ====================

    private void validateWrite(FirewallRuleBody body) {
        if (body == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String action = body.getAction() == null ? "" : body.getAction().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "动作仅支持 allow/deny/reject/limit");
        }
        body.setAction(action);
        String protocol = body.getProtocol() == null || body.getProtocol().isBlank()
                ? "tcp" : body.getProtocol().toLowerCase(Locale.ROOT);
        if (!Set.of("tcp", "udp", "any").contains(protocol)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "协议仅支持 tcp/udp/any");
        }
        body.setProtocol(protocol);
        if (body.getSource() != null && !body.getSource().isBlank()) {
            body.setSource(validSource(body.getSource().trim()));
        }
        var target = body.getTarget();
        if (target == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少规则目标");
        }
        String kind = target.getKind() == null ? "port" : target.getKind().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "目标类型仅支持 port/range/multi/any");
        }
        target.setKind(kind);
        if ("port".equals(kind) && target.getPort() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "单端口规则必须指定端口");
        }
        if ("range".equals(kind)) {
            if (target.getPort() == null || target.getPortEnd() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "端口范围必须指定起始与结束端口");
            }
            if (target.getPortEnd() < target.getPort()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "结束端口不能小于起始端口");
            }
        }
        if ("multi".equals(kind)) {
            if (target.getPorts() == null || target.getPorts().isEmpty()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "多端口至少要填一个端口");
            }
            if ("any".equals(protocol)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "多端口必须指定 tcp 或 udp 协议");
            }
        }
        if ("any".equals(kind) && (body.getSource() == null || body.getSource().isBlank())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "「任意端口」规则必须限定来源，否则过于危险");
        }
    }

    /**
     * 来源语义校验（修 C6）。
     *
     * <p>旧实现用正则 {@code [0-9a-fA-F.:/]{3,64}} 校验来源，只能挡字符集、
     * 挡不住 {@code 999.1.1.1} 或 {@code 10.0.0.0/99} 这类<b>合法字符但语义非法</b>的值；
     * 这里改为按 IP / CIDR 语义解析。
     */
    private String validSource(String source) {
        if ("any".equalsIgnoreCase(source) || "anywhere".equalsIgnoreCase(source)) {
            return "any";
        }
        String addr = source;
        int prefix = -1;
        int slash = source.indexOf('/');
        if (slash > 0) {
            addr = source.substring(0, slash);
            try {
                prefix = Integer.parseInt(source.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "来源网段前缀非法: " + source);
            }
        }
        boolean ipv6 = addr.indexOf(':') >= 0;
        int maxPrefix = ipv6 ? 128 : 32;
        if (prefix >= 0 && (prefix < 0 || prefix > maxPrefix)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "来源网段前缀需在 0-" + maxPrefix + " 之间: " + source);
        }
        try {
            InetAddress.getByName(addr);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "来源地址非法: " + source);
        }
        return source;
    }

    /**
     * 生存线护栏：若本次变更会切断 SSH / 面板访问，要求键入确认关键字。
     *
     * @param body   写入请求（新增时传）
     * @param target 被删规则（删除时传）
     */
    private void requireGuard(FirewallRuleBody body, FirewallRule target, String confirm) {
        List<Integer> ssh = sshPorts();
        List<Integer> panel = panelPorts();
        if (body != null && ("deny".equals(body.getAction()) || "reject".equals(body.getAction()))) {
            // 新增 deny/reject 规则，且覆盖了 SSH 或面板端口
            for (int p : ssh) {
                if (coversTargetBody(body, p)) {
                    requireConfirm(confirm, "SSH " + p,
                            "该规则会拒绝 SSH 端口 " + p + " 的访问，一旦生效可能无法远程登录");
                }
            }
            for (int p : panel) {
                if (coversTargetBody(body, p)) {
                    requireConfirm(confirm, "PANEL " + p,
                            "该规则会拒绝面板端口 " + p + " 的访问，一旦生效本面板将无法打开");
                }
            }
            return;
        }
        if (target != null && "allow".equals(target.getAction())) {
            for (int p : ssh) {
                if (coversTarget(target.getTo(), target.getToKind(), p)) {
                    requireConfirm(confirm, "SSH " + p,
                            "该规则是 SSH 端口 " + p + " 的放行规则，删除后可能无法远程登录");
                }
            }
            for (int p : panel) {
                if (coversTarget(target.getTo(), target.getToKind(), p)) {
                    requireConfirm(confirm, "PANEL " + p,
                            "该规则是面板端口 " + p + " 的放行规则，删除后本面板将无法打开");
                }
            }
        }
    }

    private boolean coversTargetBody(FirewallRuleBody body, int port) {
        var t = body.getTarget();
        if (t == null) {
            return false;
        }
        String kind = t.getKind();
        if ("any".equals(kind)) {
            return true;
        }
        if ("port".equals(kind)) {
            return t.getPort() != null && t.getPort() == port;
        }
        if ("range".equals(kind)) {
            return t.getPort() != null && t.getPortEnd() != null
                    && port >= t.getPort() && port <= t.getPortEnd();
        }
        if ("multi".equals(kind)) {
            return t.getPorts() != null && t.getPorts().contains(port);
        }
        return false;
    }

    private void requireConfirm(String actual, String keyword, String reason) {
        if (actual != null && actual.trim().equalsIgnoreCase(keyword)) {
            return;
        }
        ServiceException e = new ServiceException(ErrorCode.FIREWALL_GUARD_TRIGGERED, reason);
        throw e;
    }

    // ==================== 内部：编译 argv 参数 ====================

    private Map<String, Object> spec(FirewallRuleBody body) {
        Map<String, Object> spec = new LinkedHashMap<>();
        var t = body.getTarget();
        spec.put("kind", t.getKind());
        spec.put("action", body.getAction());
        spec.put("protocol", body.getProtocol());
        if (t.getPort() != null) {
            spec.put("port", t.getPort());
        }
        if (t.getPortEnd() != null) {
            spec.put("portEnd", t.getPortEnd());
        }
        if (t.getPorts() != null && !t.getPorts().isEmpty()) {
            spec.put("ports", t.getPorts());
        }
        if (body.getSource() != null && !body.getSource().isBlank()) {
            spec.put("source", body.getSource());
        }
        String comment = body.getComment() == null ? "" : body.getComment().trim();
        if (!comment.isEmpty() && !comment.startsWith(PANEL_COMMENT_PREFIX)) {
            comment = PANEL_COMMENT_PREFIX + " " + comment;
        }
        if (!comment.isEmpty()) {
            spec.put("comment", comment);
        }
        return spec;
    }

    private Map<String, Object> withoutComment(Map<String, Object> spec) {
        Map<String, Object> copy = new LinkedHashMap<>(spec);
        copy.remove("comment");
        return copy;
    }

    /**
     * 由已解析的规则反推写入参数（用于删除操作的回滚脚本）。
     *
     * <p>返回 {@code null} 表示这条规则无法被还原成 ufw 命令行（典型是应用名规则
     * {@code Nginx HTTP}），此时删除操作会被记为不可回滚——与其在回滚时拼出一条
     * 缺端口的非法命令，不如一开始就如实标记为不可撤销。
     *
     * <p>踩过的坑：{@link #TO_MULTI} 的 {@code [\d,]+} 同样能匹配单个端口，
     * 若不加逗号判断，{@code 39999/tcp} 会被误判成「多端口」，写出的回滚脚本是
     * {@code ports:[39999]} 而非 {@code port:39999}，宿主代理在 kind=port 分支上
     * 取不到 port 键，直接回「端口 必须是整数」，回滚必然失败。
     * 这里与 {@link #toKind} 保持同一判定口径。
     */
    private Map<String, Object> specFromRule(FirewallRule rule) {
        String kind = rule.getToKind();
        if (kind == null || "app".equals(kind)) {
            return null;
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kind", kind);
        spec.put("action", rule.getAction());
        String to = rule.getTo();
        String portPart = to.contains(" on ") ? to.substring(0, to.indexOf(" on ")) : to;
        String proto = "tcp";
        Matcher multi = TO_MULTI.matcher(portPart);
        Matcher single = TO_PORT.matcher(portPart);
        boolean parsed = false;
        if ("any".equals(kind)) {
            proto = "any";
            parsed = true;
        } else if (multi.matches() && portPart.indexOf(',') > 0) {
            proto = multi.group("proto");
            spec.put("ports", Arrays.stream(multi.group("nums").split(","))
                    .map(Integer::parseInt).collect(java.util.stream.Collectors.toList()));
            parsed = true;
        } else if (single.matches()) {
            if (single.group("proto") != null) {
                proto = single.group("proto");
            }
            spec.put("port", Integer.parseInt(single.group("num")));
            if (single.group("end") != null) {
                spec.put("portEnd", Integer.parseInt(single.group("end")));
            }
            parsed = true;
        }
        if (!parsed) {
            return null;
        }
        spec.put("protocol", proto);
        if (!"any".equals(rule.getSourceKind())) {
            spec.put("source", rule.getFrom());
        }
        if (rule.getComment() != null && !rule.getComment().isBlank()) {
            spec.put("comment", rule.getComment());
        }
        return spec;
    }

    private static Map<String, Object> opArgs(String op, Map<String, Object> args) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", op);
        step.put("args", args);
        return step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    // ==================== 内部：快照、差异、记录 ====================

    private String numberedSnapshot() {
        HostResult r = hostChannel.call("firewall.statusRaw", Map.of(), "读取防火墙快照", 30);
        return r.dataString("numbered");
    }

    /** 前后规则清单的差异（集合计数差） */
    private List<String> diffRules(List<FirewallRule> before, List<FirewallRule> after) {
        Map<String, Integer> b = countBy(before);
        Map<String, Integer> a = countBy(after);
        List<String> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());
        for (String key : keys) {
            int cb = b.getOrDefault(key, 0);
            int ca = a.getOrDefault(key, 0);
            if (ca > cb) {
                out.add("+ " + key.replace('|', ' ') + " ×" + (ca - cb));
            } else if (cb > ca) {
                out.add("- " + key.replace('|', ' ') + " ×" + (cb - ca));
            }
        }
        return out;
    }

    private Map<String, Integer> countBy(List<FirewallRule> rules) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (FirewallRule r : rules) {
            map.merge(r.getFingerprint(), 1, Integer::sum);
        }
        return map;
    }

    private Long recordChange(String op, String ruleDesc, String before, String after,
                              List<String> diff, List<Map<String, Object>> undo,
                              boolean guardAck, Integer watchdogSec, boolean ok,
                              String errorMsg) {
        OpsFirewallChange row = new OpsFirewallChange();
        row.setBackend("ufw");
        row.setOp(op);
        row.setRuleDesc(ruleDesc == null ? "" : cut(ruleDesc, 255));
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        try {
            row.setDiffJson(objectMapper.writeValueAsString(diff == null ? List.of() : diff));
            if (undo != null && !undo.isEmpty()) {
                row.setUndoJson(objectMapper.writeValueAsString(undo));
            }
        } catch (Exception e) {
            log.warn("序列化防火墙变更差异失败: {}", e.getMessage());
        }
        row.setGuardAck(guardAck ? 1 : 0);
        row.setWatchdogSeconds(watchdogSec);
        row.setRollbackable(undo != null && !undo.isEmpty() ? 1 : 0);
        row.setRolledBack(0);
        row.setResult(ok ? 0 : 1);
        row.setErrorMsg(errorMsg == null ? null : cut(errorMsg, 500));
        row.setOperator(currentOperator());
        row.setCreatedAt(LocalDateTime.now());
        changeMapper.insert(row);
        return row.getId();
    }

    private FirewallActionResultVO result(HostResult r, Long changeId, List<String> diff,
                                          FirewallActionResultVO.WatchdogBrief watchdog) {
        FirewallActionResultVO vo = new FirewallActionResultVO();
        vo.setOk(r.getExitCode() == 0);
        vo.setChangeId(changeId);
        vo.setDiff(diff == null ? List.of() : diff);
        vo.setWatchdog(watchdog);
        Object command = r.getData() == null ? null : r.getData().get("command");
        vo.setCommand(command == null ? null : str(command));
        if (r.getExitCode() == 0) {
            vo.setMessage("操作成功");
        } else {
            vo.setMessage("操作失败：" + r.errorText());
            throw new ServiceException(ErrorCode.ERROR.getCode(), vo.getMessage());
        }
        return vo;
    }

    private static String describe(FirewallRuleBody body) {
        var t = body.getTarget();
        StringBuilder sb = new StringBuilder(body.getAction()).append(' ');
        if (t != null) {
            switch (str(t.getKind())) {
                case "range" -> sb.append(t.getPort()).append(':').append(t.getPortEnd());
                case "multi" -> sb.append(t.getPorts());
                case "any" -> sb.append("any");
                default -> sb.append(t.getPort());
            }
        }
        if (body.getProtocol() != null && !"any".equals(body.getProtocol())) {
            sb.append('/').append(body.getProtocol());
        }
        if (body.getSource() != null && !body.getSource().isBlank()) {
            sb.append(" from ").append(body.getSource());
        }
        return sb.toString();
    }

    private static String describe(FirewallRule rule) {
        return rule.getAction() + " " + rule.getTo() + " from " + rule.getFrom();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String currentOperator() {
        try {
            return LoginHelper.isLogin() ? LoginHelper.getUsername() : "system";
        } catch (RuntimeException e) {
            return "system";
        }
    }
}
