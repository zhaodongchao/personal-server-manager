package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.RegexTemplateBody;
import com.serverpanel.tools.dto.RegexTemplateQuery;
import com.serverpanel.tools.dto.RegexTemplateVO;
import com.serverpanel.tools.service.RegexTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 正则模板接口：全局共享模板的分页 / 全量 / 增删改。
 *
 * <p>审计口径：与 quick-nav 一致「记录入参」——模板是要共享给所有用户的
 * 公开资产，谁在什么时候改了公共模板需要可追溯，不存在敏感内容不落审计的问题。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/regex/template")
@RequiredArgsConstructor
public class RegexTemplateController {

    private final RegexTemplateService regexTemplateService;

    /** 模板分页（管理 Tab 用） */
    @SaCheckPermission("tools:regex:list")
    @GetMapping("/page")
    public R<PageResult<RegexTemplateVO>> page(@Valid RegexTemplateQuery query) {
        return R.ok(regexTemplateService.page(query));
    }

    /** 模板全量列表（测试 Tab 下拉联动用，按 sort、name 排序） */
    @SaCheckPermission("tools:regex:list")
    @GetMapping("/list")
    public R<List<RegexTemplateVO>> list() {
        return R.ok(regexTemplateService.list());
    }

    /** 新增模板 */
    @SaCheckPermission("tools:regex:template:add")
    @Audit(module = "tools", action = "regex:template:add")
    @PostMapping
    public R<Void> create(@Valid @RequestBody RegexTemplateBody body) {
        regexTemplateService.create(body);
        return R.ok();
    }

    /** 编辑模板 */
    @SaCheckPermission("tools:regex:template:edit")
    @Audit(module = "tools", action = "regex:template:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody RegexTemplateBody body) {
        regexTemplateService.update(id, body);
        return R.ok();
    }

    /** 删除模板 */
    @SaCheckPermission("tools:regex:template:delete")
    @Audit(module = "tools", action = "regex:template:delete", risky = true)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        regexTemplateService.delete(id);
        return R.ok();
    }
}
