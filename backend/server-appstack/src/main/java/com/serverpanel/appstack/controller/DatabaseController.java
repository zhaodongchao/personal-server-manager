package com.serverpanel.appstack.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.appstack.dto.AdoptBody;
import com.serverpanel.appstack.dto.DatabaseBody;
import com.serverpanel.appstack.dto.DatabaseCreateResult;
import com.serverpanel.appstack.dto.DatabaseOverviewVO;
import com.serverpanel.appstack.dto.RestoreBody;
import com.serverpanel.appstack.entity.AppDatabase;
import com.serverpanel.appstack.service.DatabaseService;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面板代管 MySQL 库管理接口。
 */
@RestController
@RequestMapping("/api/v1/appstack/database")
@RequiredArgsConstructor
public class DatabaseController {

    private final DatabaseService databaseService;

    @SaCheckPermission("appstack:database:list")
    @GetMapping("/page")
    public R<PageResult<AppDatabase>> page(PageQuery query,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(databaseService.page(query, keyword));
    }

    @SaCheckPermission("appstack:database:list")
    @GetMapping("/charsets")
    public R<List<String>> charsets() {
        return R.ok(databaseService.charsets());
    }

    /** 浏览 MySQL 全部真实库（含纳管标记），纳管入口的数据源 */
    @SaCheckPermission("appstack:database:list")
    @GetMapping("/overview")
    public R<List<DatabaseOverviewVO>> overview() {
        return R.ok(databaseService.overview());
    }

    /** 纳管一个 MySQL 中已存在的库（登记进面板统一管理） */
    @Audit(module = "appstack", action = "database:adopt", risky = true)
    @SaCheckPermission("appstack:database:add")
    @PostMapping("/adopt")
    public R<Void> adopt(@Valid @RequestBody AdoptBody body) {
        databaseService.adopt(body);
        return R.ok();
    }

    @Audit(module = "appstack", action = "database:add", risky = true)
    @SaCheckPermission("appstack:database:add")
    @PostMapping
    public R<DatabaseCreateResult> create(@Valid @RequestBody DatabaseBody body) {
        return R.ok(databaseService.create(body));
    }

    @Audit(module = "appstack", action = "database:delete", risky = true)
    @SaCheckPermission("appstack:database:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        databaseService.delete(id);
        return R.ok();
    }

    @Audit(module = "appstack", action = "database:backup")
    @SaCheckPermission("appstack:database:backup")
    @PostMapping("/{id}/backup")
    public R<String> backup(@PathVariable Long id) {
        return R.ok(databaseService.backup(id));
    }

    @Audit(module = "appstack", action = "database:restore", risky = true)
    @SaCheckPermission("appstack:database:backup")
    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable Long id, @Valid @RequestBody RestoreBody body) {
        databaseService.restore(id, body);
        return R.ok();
    }
}
