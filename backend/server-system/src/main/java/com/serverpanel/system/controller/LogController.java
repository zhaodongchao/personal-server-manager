package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.log.AuditLogVO;
import com.serverpanel.system.entity.SysAuditLog;
import com.serverpanel.system.entity.SysLoginLog;
import com.serverpanel.system.mapper.SysAuditLogMapper;
import com.serverpanel.system.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志查询接口（审计 + 登录日志，只读）。
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class LogController {

    private final SysAuditLogMapper auditLogMapper;
    private final SysLoginLogMapper loginLogMapper;

    /**
     * 审计日志分页。
     *
     * <p>返回 VO 而不是实体：实体字段名贴合数据库列（requestUri / durationMs / errorMsg），
     * 前端表格按 uri / costMs / error 取值，直接返回会让这三列恒为空；
     * 且雪花 id 以数字出参会丢精度，而前端用它做表格行键。
     */
    @SaCheckPermission("system:audit:list")
    @GetMapping("/audit-log/page")
    public R<PageResult<AuditLogVO>> auditPage(PageQuery query,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer bizCode) {
        Page<SysAuditLog> page = auditLogMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysAuditLog>()
                .like(operator != null && !operator.isBlank(), SysAuditLog::getOperator, operator)
                .eq(module != null && !module.isBlank(), SysAuditLog::getModule, module)
                .eq(bizCode != null, SysAuditLog::getBizCode, bizCode)
                .orderByDesc(SysAuditLog::getCreatedAt));
        return R.ok(PageResult.of(page.getRecords().stream().map(AuditLogVO::of).toList(),
            page.getTotal(), query.getPageNum(), query.getPageSize()));
    }

    @SaCheckPermission("system:loginlog:list")
    @GetMapping("/login-log/page")
    public R<PageResult<SysLoginLog>> loginPage(PageQuery query,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status) {
        Page<SysLoginLog> page = loginLogMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysLoginLog>()
                .like(username != null && !username.isBlank(), SysLoginLog::getUsername, username)
                .eq(status != null, SysLoginLog::getStatus, status)
                .orderByDesc(SysLoginLog::getCreatedAt));
        return R.ok(PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize()));
    }
}
