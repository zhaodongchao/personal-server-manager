package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.FirewallRuleBody;
import com.serverpanel.ops.dto.FirewallStatus;
import com.serverpanel.ops.service.FirewallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 防火墙接口。
 */
@RestController
@RequestMapping("/api/v1/ops/firewall")
@RequiredArgsConstructor
public class FirewallController {

    private final FirewallService firewallService;

    @SaCheckPermission("ops:firewall:list")
    @GetMapping("/status")
    public R<FirewallStatus> status() {
        return R.ok(firewallService.status());
    }

    @Audit(module = "ops", action = "firewall:add", risky = true)
    @SaCheckPermission("ops:firewall:write")
    @PostMapping("/rule")
    public R<Void> addRule(@Valid @RequestBody FirewallRuleBody body) {
        firewallService.addRule(body);
        return R.ok();
    }

    @Audit(module = "ops", action = "firewall:delete", risky = true)
    @SaCheckPermission("ops:firewall:write")
    @DeleteMapping("/rule")
    public R<Void> deleteRule(@Valid @RequestBody FirewallRuleBody body) {
        firewallService.deleteRule(body);
        return R.ok();
    }
}
