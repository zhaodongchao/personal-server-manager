package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.BankBinBody;
import com.serverpanel.tools.dto.PhoneSegmentBody;
import com.serverpanel.tools.dto.RegionNodeVO;
import com.serverpanel.tools.dto.SyncStatusVO;
import com.serverpanel.tools.entity.BasedataSyncLog;
import com.serverpanel.tools.entity.SysBankBin;
import com.serverpanel.tools.entity.SysPhoneSegment;
import com.serverpanel.tools.entity.SysRegion;
import com.serverpanel.tools.service.BankBinService;
import com.serverpanel.tools.service.BasedataSyncService;
import com.serverpanel.tools.service.PhoneSegmentService;
import com.serverpanel.tools.service.RegionService;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * 基础数据管理接口（行政区划 / 手机号段 / 银行卡 BIN / 同步）。
 *
 * <p>挂在 /api/v1/system/basedata：管理对象是面板的基础字典数据，
 * 属系统管理职能；实现放在 server-tools 是因为消费方（证件解析）在这里，
 * 避免为一张表造跨模块依赖。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@RestController
@RequestMapping("/api/v1/system/basedata")
@RequiredArgsConstructor
public class BasedataController {

    private final RegionService regionService;

    private final PhoneSegmentService phoneSegmentService;

    private final BankBinService bankBinService;

    private final BasedataSyncService basedataSyncService;

    // ==================== 行政区划 ====================

    /** 懒加载子节点（parentCode 为空返回省级） */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/region/tree")
    public R<List<RegionNodeVO>> regionTree(@RequestParam(required = false) String parentCode) {
        return R.ok(regionService.tree(parentCode));
    }

    /** 分页检索（keyword 命中 code/name） */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/region/page")
    public R<PageResult<SysRegion>> regionPage(PageQuery query,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer level,
                                               @RequestParam(required = false) Integer status) {
        return R.ok(regionService.page(query, keyword, level, status));
    }

    // ==================== 手机号段 ====================

    /** 号段分页（operator 筛选 + prefix 检索） */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/phone-segment/page")
    public R<PageResult<SysPhoneSegment>> phoneSegmentPage(PageQuery query,
                                                           @RequestParam(required = false) String operator,
                                                           @RequestParam(required = false) String keyword) {
        return R.ok(phoneSegmentService.page(query, operator, keyword));
    }

    /** 新增号段 */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:phone-segment:add")
    @PostMapping("/phone-segment")
    public R<Void> createPhoneSegment(@Valid @RequestBody PhoneSegmentBody body) {
        phoneSegmentService.create(body);
        return R.ok();
    }

    /** 编辑号段 */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:phone-segment:edit")
    @PutMapping("/phone-segment")
    public R<Void> updatePhoneSegment(@Valid @RequestBody PhoneSegmentBody body) {
        phoneSegmentService.update(body);
        return R.ok();
    }

    /** 删除号段 */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:phone-segment:delete")
    @DeleteMapping("/phone-segment/{id}")
    public R<Void> deletePhoneSegment(@PathVariable Long id) {
        phoneSegmentService.delete(id);
        return R.ok();
    }

    // ==================== 银行卡 BIN ====================

    /** BIN 分页（bankName 筛选 + bin/名称检索） */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/bank-bin/page")
    public R<PageResult<SysBankBin>> bankBinPage(PageQuery query,
                                                 @RequestParam(required = false) String bankName,
                                                 @RequestParam(required = false) String keyword) {
        return R.ok(bankBinService.page(query, bankName, keyword));
    }

    /** 新增 BIN */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:bank-bin:add")
    @PostMapping("/bank-bin")
    public R<Void> createBankBin(@Valid @RequestBody BankBinBody body) {
        bankBinService.create(body);
        return R.ok();
    }

    /** 编辑 BIN */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:bank-bin:edit")
    @PutMapping("/bank-bin")
    public R<Void> updateBankBin(@Valid @RequestBody BankBinBody body) {
        bankBinService.update(body);
        return R.ok();
    }

    /** 删除 BIN */
    @SaCheckPermission("system:basedata:edit")
    @Audit(module = "system", action = "basedata:bank-bin:delete")
    @DeleteMapping("/bank-bin/{id}")
    public R<Void> deleteBankBin(@PathVariable Long id) {
        bankBinService.delete(id);
        return R.ok();
    }

    // ==================== 同步 ====================

    /** 各类型最近一次同步状态 + 当前行数（状态卡数据源，前端 2s 轮询） */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/sync/status")
    public R<SyncStatusVO> syncStatus() {
        return R.ok(basedataSyncService.status());
    }

    /** 同步日志分页 */
    @SaCheckPermission("system:basedata:list")
    @GetMapping("/sync/log")
    public R<PageResult<BasedataSyncLog>> syncLogPage(PageQuery query,
                                                      @RequestParam(required = false) String dataType) {
        return R.ok(basedataSyncService.logPage(query, dataType));
    }

    /** 手动触发同步（异步执行，立即返回；type=region/phone/bin） */
    @SaCheckPermission("system:basedata:sync")
    @Audit(module = "system", action = "basedata:sync", recordParams = false)
    @PostMapping("/sync/{type}")
    public R<Void> triggerSync(@PathVariable String type) {
        basedataSyncService.triggerAsync(type);
        return R.ok();
    }
}
