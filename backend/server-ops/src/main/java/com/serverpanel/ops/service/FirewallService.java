package com.serverpanel.ops.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.ops.dto.FirewallRule;
import com.serverpanel.ops.dto.FirewallRuleBody;
import com.serverpanel.ops.dto.FirewallStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 防火墙管理：自动探测 ufw / firewalld，抽象统一的端口规则操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirewallService {

    /** ufw status numbered 规则行 */
    private static final Pattern UFW_RULE =
        Pattern.compile("^\\[\\s*(\\d+)\\]\\s+(\\S+)(\\s+\\(v6\\))?\\s+(\\S+)\\s+(.*)$");

    private final CommandExecutor commandExecutor;

    /** 探测后端并返回整体状态 */
    public FirewallStatus status() {
        if (isUfwActive()) {
            return ufwStatus();
        }
        if (isFirewalldActive()) {
            return firewalldStatus();
        }
        FirewallStatus st = new FirewallStatus();
        st.setBackend("none");
        st.setActive(false);
        st.setRules(List.of());
        return st;
    }

    /** 新增规则 */
    public void addRule(FirewallRuleBody body) {
        String backend = detectBackend();
        if ("ufw".equals(backend)) {
            ufwAdd(body);
        } else if ("firewalld".equals(backend)) {
            if (!"allow".equals(body.getAction())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "firewalld 仅支持放行(allow)规则");
            }
            firewalldAdd(body);
        } else {
            throw new ServiceException(ErrorCode.FIREWALL_UNAVAILABLE);
        }
    }

    /** 删除规则（按 端口/协议/动作 匹配） */
    public void deleteRule(FirewallRuleBody body) {
        String backend = detectBackend();
        if ("ufw".equals(backend)) {
            ufwDelete(body);
        } else if ("firewalld".equals(backend)) {
            firewalldDelete(body);
        } else {
            throw new ServiceException(ErrorCode.FIREWALL_UNAVAILABLE);
        }
    }

    // ==================== 探测 ====================

    private String detectBackend() {
        if (isUfwActive()) {
            return "ufw";
        }
        if (isFirewalldActive()) {
            return "firewalld";
        }
        return "none";
    }

    private boolean isUfwActive() {
        try {
            ExecResult r = commandExecutor.exec("ufw", "status");
            return r.getExitCode() == 0 && r.getStdout().contains("Status: active");
        } catch (ServiceException e) {
            return false;
        }
    }

    private boolean isFirewalldActive() {
        try {
            ExecResult r = commandExecutor.exec("firewall-cmd", "--state");
            return r.getExitCode() == 0 && r.getStdout().trim().equals("running");
        } catch (ServiceException e) {
            return false;
        }
    }

    // ==================== ufw ====================

    private FirewallStatus ufwStatus() {
        ExecResult r = commandExecutor.exec("ufw", "status", "numbered");
        FirewallStatus st = new FirewallStatus();
        st.setBackend("ufw");
        st.setActive(true);
        List<FirewallRule> rules = new ArrayList<>();
        for (String line : r.getStdout().split("\\r?\\n")) {
            Matcher m = UFW_RULE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            FirewallRule rule = new FirewallRule();
            rule.setId(m.group(1));
            rule.setPort(m.group(2) + (m.group(3) == null ? "" : m.group(3)));
            rule.setAction(m.group(4).toLowerCase());
            rule.setSource(m.group(5).trim().isEmpty() ? "-" : m.group(5).trim());
            rules.add(rule);
        }
        st.setRules(rules);
        return st;
    }

    private void ufwAdd(FirewallRuleBody body) {
        String portSpec = body.getPort() + "/" + body.getProtocol();
        ExecResult r;
        if (body.getSource() != null && !body.getSource().isBlank()) {
            r = commandExecutor.exec("ufw", body.getAction(), "from", body.getSource(),
                "to", "any", "port", portSpec);
        } else {
            r = commandExecutor.exec("ufw", body.getAction(), portSpec);
        }
        throwIfFailed(r);
    }

    private void ufwDelete(FirewallRuleBody body) {
        String portSpec = body.getPort() + "/" + body.getProtocol();
        ExecResult r;
        if (body.getSource() != null && !body.getSource().isBlank()) {
            r = commandExecutor.exec("ufw", "delete", body.getAction(), "from",
                body.getSource(), "to", "any", "port", portSpec);
        } else {
            r = commandExecutor.exec("ufw", "delete", body.getAction(), portSpec);
        }
        throwIfFailed(r);
    }

    // ==================== firewalld ====================

    private FirewallStatus firewalldStatus() {
        ExecResult r = commandExecutor.exec("firewall-cmd", "--list-all");
        FirewallStatus st = new FirewallStatus();
        st.setBackend("firewalld");
        st.setActive(true);
        List<FirewallRule> rules = new ArrayList<>();
        int idx = 1;
        for (String line : r.getStdout().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("ports:")) {
                continue;
            }
            for (String p : trimmed.substring("ports:".length()).trim().split("\\s+")) {
                if (p.isEmpty()) {
                    continue;
                }
                FirewallRule rule = new FirewallRule();
                rule.setId(String.valueOf(idx++));
                rule.setPort(p);
                rule.setAction("allow");
                rule.setSource("-");
                rules.add(rule);
            }
        }
        st.setRules(rules);
        return st;
    }

    private void firewalldAdd(FirewallRuleBody body) {
        String portSpec = body.getPort() + "/" + body.getProtocol();
        throwIfFailed(commandExecutor.exec(
            "firewall-cmd", "--permanent", "--add-port=" + portSpec));
        throwIfFailed(commandExecutor.exec("firewall-cmd", "--reload"));
    }

    private void firewalldDelete(FirewallRuleBody body) {
        String portSpec = body.getPort() + "/" + body.getProtocol();
        throwIfFailed(commandExecutor.exec(
            "firewall-cmd", "--permanent", "--remove-port=" + portSpec));
        throwIfFailed(commandExecutor.exec("firewall-cmd", "--reload"));
    }

    private void throwIfFailed(ExecResult r) {
        if (r.getExitCode() != 0) {
            String msg = r.getStderr().isBlank() ? r.getStdout() : r.getStderr();
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                msg.isBlank() ? "防火墙操作失败" : msg.trim());
        }
    }
}
