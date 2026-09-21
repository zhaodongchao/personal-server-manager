package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.FirewallActionResultVO;
import com.serverpanel.ops.dto.FirewallDefaultPolicyBody;
import com.serverpanel.ops.dto.FirewallGuard;
import com.serverpanel.ops.dto.FirewallRuleBody;
import com.serverpanel.ops.dto.FirewallStatus;
import com.serverpanel.ops.entity.OpsFirewallChange;
import com.serverpanel.ops.service.FirewallService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 防火墙接口。
 *
 * <p>除「读状态」外，其余操作全部是高危动作：读需要 {@code ops:firewall:list}，
 * 增删规则需要 {@code ops:firewall:write}，全局开关与看门狗需要 {@code ops:firewall:danger}，
 * 回滚需要 {@code ops:firewall:rollback}。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/firewall")
@RequiredArgsConstructor
public class FirewallController {

    private final FirewallService firewallService;

    private final HttpServletRequest request;

    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/status")
    public R<FirewallStatus> status() {
        return R.ok(firewallService.status(clientIp()));
    }

    /** 生存线信息：SSH 端口、面板端口、来源 IP 与风险提示 */
    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/guard")
    public R<FirewallGuard> guard() {
        return R.ok(firewallService.guard(clientIp()));
    }

    /** ufw show raw 原文（排障用） */
    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/raw")
    public R<String> raw() {
        return R.ok(firewallService.raw());
    }

    @Audit(module = "ops", action = "firewall:add", risky = true)
    @SaCheckPermission("ops:firewall:write")
    @PostMapping("/rule")
    public R<FirewallActionResultVO> addRule(@RequestBody FirewallRuleBody body) {
        return R.ok(firewallService.addRule(body));
    }

    /**
     * 删除规则：按编号删除，但会先校验指纹（to|action|from），
     * 防止编号在并发增删后漂移导致误删另一条规则。
     */
    @Audit(module = "ops", action = "firewall:delete", risky = true)
    @SaCheckPermission("ops:firewall:write")
    @DeleteMapping("/rule")
    public R<FirewallActionResultVO> deleteRule(@RequestBody FirewallRuleBody body) {
        return R.ok(firewallService.deleteRule(body));
    }

    /** 启用防火墙（L3：默认挂看门狗，到期未确认自动回滚） */
    @Audit(module = "ops", action = "firewall:enable", risky = true)
    @SaCheckPermission("ops:firewall:danger")
    @PostMapping("/enable")
    public R<FirewallActionResultVO> enable(@RequestBody(required = false) ConfirmBody body) {
        return R.ok(firewallService.setEnabled(true, body == null ? null : body.getConfirm()));
    }

    /** 停用防火墙（L3） */
    @Audit(module = "ops", action = "firewall:disable", risky = true)
    @SaCheckPermission("ops:firewall:danger")
    @PostMapping("/disable")
    public R<FirewallActionResultVO> disable(@RequestBody(required = false) ConfirmBody body) {
        return R.ok(firewallService.setEnabled(false, body == null ? null : body.getConfirm()));
    }

    /** 设置默认策略（L3） */
    @Audit(module = "ops", action = "firewall:default-policy", risky = true)
    @SaCheckPermission("ops:firewall:danger")
    @PostMapping("/default-policy")
    public R<FirewallActionResultVO> defaultPolicy(@RequestBody FirewallDefaultPolicyBody body) {
        return R.ok(firewallService.setDefault(body, body.getConfirm()));
    }

    @Audit(module = "ops", action = "firewall:reload", risky = true)
    @SaCheckPermission("ops:firewall:write")
    @PostMapping("/reload")
    public R<FirewallActionResultVO> reload() {
        return R.ok(firewallService.reload());
    }

    /** 变更历史（含前后快照与 diff） */
    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/changes")
    public R<PageResult<OpsFirewallChange>> changes(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(firewallService.changes(pageNum, pageSize));
    }

    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/changes/{id}")
    public R<OpsFirewallChange> changeDetail(@PathVariable Long id) {
        return R.ok(firewallService.changeDetail(id));
    }

    /** 回滚某次变更（L3） */
    @Audit(module = "ops", action = "firewall:rollback", risky = true)
    @SaCheckPermission("ops:firewall:rollback")
    @PostMapping("/changes/{id}/rollback")
    public R<FirewallActionResultVO> rollback(@PathVariable Long id,
                                              @RequestBody(required = false) ConfirmBody body) {
        return R.ok(firewallService.rollback(id, body == null ? null : body.getConfirm()));
    }

    /** 看门狗状态：变更保护倒计时；未启用返回 null */
    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/guard/watchdog")
    public R<FirewallActionResultVO.WatchdogBrief> watchdog() {
        return R.ok(firewallService.watchdog());
    }

    /** 注册看门狗（手动触发；全局开关类操作会自动注册） */
    @Audit(module = "ops", action = "firewall:watchdog", risky = true)
    @SaCheckPermission("ops:firewall:danger")
    @PostMapping("/guard/watchdog")
    public R<FirewallActionResultVO.WatchdogBrief> armWatchdog(
            @RequestBody(required = false) WatchdogBody body) {
        return R.ok(firewallService.armWatchdogManual(
                body == null ? null : body.getSeconds(),
                body == null ? null : body.getAction()));
    }

    /** 「保留变更」——撤销看门狗，不再自动回滚 */
    @Audit(module = "ops", action = "firewall:watchdog-confirm", risky = true)
    @SaCheckPermission("ops:firewall:danger")
    @PostMapping("/guard/confirm")
    public R<Void> confirmWatchdog() {
        firewallService.confirmWatchdog();
        return R.ok();
    }

    /**
     * 客户端 IP：用于生存线判断（提示用户「你现在从哪个 IP 连进来」）。
     * 走反向代理部署时取 X-Forwarded-For 的首段。
     */
    private String clientIp() {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    /** 仅承载二次确认关键字的请求体 */
    public static class ConfirmBody {
        private String confirm;

        public String getConfirm() {
            return confirm;
        }

        public void setConfirm(String confirm) {
            this.confirm = confirm;
        }
    }

    /** 手动注册看门狗的请求体 */
    public static class WatchdogBody {
        private Integer seconds;
        private String action;

        public Integer getSeconds() {
            return seconds;
        }

        public void setSeconds(Integer seconds) {
            this.seconds = seconds;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
