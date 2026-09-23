package com.serverpanel.appstack.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.appstack.dto.IdSourceBody;
import com.serverpanel.appstack.dto.IdSourceInitVO;
import com.serverpanel.appstack.dto.IdSourceStatusVO;
import com.serverpanel.appstack.dto.IdSourceVO;
import com.serverpanel.appstack.service.IdSourceService;
import com.serverpanel.appstack.service.source.JdbcSupport;
import com.serverpanel.appstack.service.source.MysqlIdSource;
import com.serverpanel.appstack.service.source.PostgresIdSource;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.common.id.IdSourceOption;
import com.serverpanel.common.id.IdSourceProbe;
import com.serverpanel.framework.crypto.SecretCipher;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 取号数据源管理接口（挂在应用栈下，与「数据库」同页不同区块）。
 *
 * <p><b>权限为什么是 OR</b>：这套接口有两个消费方 —— 数据库页（应用栈权限）
 * 与 ID 生成器的数据源下拉（日常工具权限）。任一权限都足以读取下拉；
 * 而写操作（新增/编辑/删除/初始化）只在数据库页出现，用应用栈自己的权限码即可。
 *
 * <p><b>审计为什么对 save 关闭入参</b>：请求体里有数据库口令。审计的脱敏词表按
 * key 名匹配（password / secret / token 之类），而这里的字段就叫 {@code password}，
 * 理论上会被脱敏 —— 但口令类端点一律从源头关掉入参记录更稳妥：脱敏是「尽力而为」，
 * 不记录才是确定性的。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@RestController
@RequestMapping("/api/v1/appstack/id-source")
@RequiredArgsConstructor
public class IdSourceController {

    private final IdSourceService idSourceService;

    private final JdbcSupport jdbcSupport;

    private final SecretCipher secretCipher;

    // ==========================================================================
    // 查询
    // ==========================================================================

    @SaCheckPermission(value = {"appstack:database:list", "tools:id:list"}, mode = SaMode.OR)
    @GetMapping("/page")
    public R<PageResult<IdSourceVO>> page(PageQuery query,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(idSourceService.page(query, keyword));
    }

    /** 数据源下拉（ID 生成器页面用） */
    @SaCheckPermission(value = {"appstack:database:list", "tools:id:list"}, mode = SaMode.OR)
    @GetMapping("/options")
    public R<List<IdSourceOption>> options() {
        return R.ok(idSourceService.options());
    }

    /** 环境状态：密钥是否可用、允许的库、默认取号对象名 */
    @SaCheckPermission(value = {"appstack:database:list", "tools:id:list"}, mode = SaMode.OR)
    @GetMapping("/status")
    public R<IdSourceStatusVO> status() {
        List<String> allowed = new ArrayList<>(jdbcSupport.allowedDatabases());
        java.util.Collections.sort(allowed);
        return R.ok(new IdSourceStatusVO(secretCipher.available(), allowed,
                PostgresIdSource.DEFAULT_SEQUENCE, MysqlIdSource.DEFAULT_TABLE,
                JdbcSupport.AUTO_PREFIX));
    }

    // ==========================================================================
    // 写操作
    // ==========================================================================

    @Audit(module = "appstack", action = "id-source:save", risky = true, recordParams = false)
    @SaCheckPermission("appstack:id-source:save")
    @PostMapping
    public R<String> create(@Valid @RequestBody IdSourceBody body) {
        return R.ok(String.valueOf(idSourceService.create(body)));
    }

    @Audit(module = "appstack", action = "id-source:save", risky = true, recordParams = false)
    @SaCheckPermission("appstack:id-source:save")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IdSourceBody body) {
        idSourceService.update(id, body);
        return R.ok();
    }

    @Audit(module = "appstack", action = "id-source:delete", risky = true)
    @SaCheckPermission("appstack:id-source:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        idSourceService.delete(id);
        return R.ok();
    }

    /** 连通性探测（只读：select version()） */
    @Audit(module = "appstack", action = "id-source:probe")
    @SaCheckPermission("appstack:id-source:probe")
    @PostMapping("/{id}/probe")
    public R<IdSourceProbe> probe(@PathVariable Long id) {
        return R.ok(idSourceService.probe(id));
    }

    /**
     * 初始化取号对象（建序列 / 建自增表），幂等。
     *
     * <p>标记 risky 是因为它会在目标库里执行真实 DDL —— 虽然对象名受
     * {@code psm_} 前缀约束，但仍属「改了库里的东西」，应当留痕。
     */
    @Audit(module = "appstack", action = "id-source:init", risky = true)
    @SaCheckPermission("appstack:id-source:probe")
    @PostMapping("/{id}/init")
    public R<IdSourceInitVO> initialize(@PathVariable Long id) {
        Map<String, String> meta = idSourceService.targetMap(id);
        String message = idSourceService.initialize(id);
        IdSourceProbe probe = idSourceService.probe(id);
        return R.ok(new IdSourceInitVO(message, meta.get("target"),
                probe.ok() ? probe.serverVersion() : null));
    }
}
