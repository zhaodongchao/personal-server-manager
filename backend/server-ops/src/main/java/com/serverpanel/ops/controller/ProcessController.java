package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.ProcessInfo;
import com.serverpanel.ops.service.ProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 进程管理接口。
 */
@RestController
@RequestMapping("/api/v1/ops/process")
@RequiredArgsConstructor
public class ProcessController {

    private final ProcessService processService;

    @SaCheckPermission("ops:process:list")
    @GetMapping("/list")
    public R<List<ProcessInfo>> list(@RequestParam(required = false) String keyword) {
        return R.ok(processService.list(keyword));
    }

    @Audit(module = "ops", action = "process:kill", risky = true)
    @SaCheckPermission("ops:process:kill")
    @PostMapping("/{pid}/kill")
    public R<Void> kill(@PathVariable long pid) {
        processService.kill(pid);
        return R.ok();
    }
}
