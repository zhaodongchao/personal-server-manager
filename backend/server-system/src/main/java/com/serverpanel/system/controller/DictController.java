package com.serverpanel.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.system.entity.SysDictData;
import com.serverpanel.system.entity.SysDictType;
import com.serverpanel.system.service.SysDictService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典管理接口（类型 + 数据）。
 */
@RestController
@RequestMapping("/api/v1/system/dict")
@RequiredArgsConstructor
public class DictController {

    private final SysDictService dictService;

    // ===== 字典类型 =====

    @SaCheckPermission("system:dict:list")
    @GetMapping("/type/page")
    public R<PageResult<SysDictType>> typePage(PageQuery query,
            @RequestParam(required = false) String keyword) {
        return R.ok(dictService.typePage(query, keyword));
    }

    @Audit(module = "system", action = "dict-type:add")
    @SaCheckPermission("system:dict:add")
    @PostMapping("/type")
    public R<Void> typeCreate(@Valid @RequestBody SysDictType body) {
        dictService.typeCreate(body);
        return R.ok();
    }

    @Audit(module = "system", action = "dict-type:edit")
    @SaCheckPermission("system:dict:edit")
    @PutMapping("/type")
    public R<Void> typeUpdate(@Valid @RequestBody SysDictType body) {
        dictService.typeUpdate(body);
        return R.ok();
    }

    @Audit(module = "system", action = "dict-type:delete", risky = true)
    @SaCheckPermission("system:dict:delete")
    @DeleteMapping("/type/{id}")
    public R<Void> typeDelete(@PathVariable Long id) {
        dictService.typeDelete(id);
        return R.ok();
    }

    // ===== 字典数据 =====

    @SaCheckPermission("system:dict:list")
    @GetMapping("/data/page")
    public R<PageResult<SysDictData>> dataPage(PageQuery query,
            @RequestParam(required = false) String dictType) {
        return R.ok(dictService.dataPage(query, dictType));
    }

    /** 按类型取启用字典（下拉数据源，仅需登录） */
    @GetMapping("/data/type/{dictType}")
    public R<List<SysDictData>> dataByType(@PathVariable String dictType) {
        return R.ok(dictService.dataByType(dictType));
    }

    @Audit(module = "system", action = "dict-data:add")
    @SaCheckPermission("system:dict:add")
    @PostMapping("/data")
    public R<Void> dataCreate(@Valid @RequestBody SysDictData body) {
        dictService.dataCreate(body);
        return R.ok();
    }

    @Audit(module = "system", action = "dict-data:edit")
    @SaCheckPermission("system:dict:edit")
    @PutMapping("/data")
    public R<Void> dataUpdate(@Valid @RequestBody SysDictData body) {
        dictService.dataUpdate(body);
        return R.ok();
    }

    @Audit(module = "system", action = "dict-data:delete")
    @SaCheckPermission("system:dict:delete")
    @DeleteMapping("/data/{id}")
    public R<Void> dataDelete(@PathVariable Long id) {
        dictService.dataDelete(id);
        return R.ok();
    }
}
