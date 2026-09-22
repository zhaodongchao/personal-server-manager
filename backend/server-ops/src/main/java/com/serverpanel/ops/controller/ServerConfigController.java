package com.serverpanel.ops.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.ServerConfigActionBody;
import com.serverpanel.ops.dto.ServerConfigApplyVO;
import com.serverpanel.ops.dto.ServerConfigCategoryVO;
import com.serverpanel.ops.dto.ServerConfigChangeVO;
import com.serverpanel.ops.dto.ServerConfigItemBody;
import com.serverpanel.ops.dto.ServerConfigItemVO;
import com.serverpanel.ops.dto.ServerConfigPreviewVO;
import com.serverpanel.ops.service.ServerConfigService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

/**
 * 服务器配置管理接口（`/api/v1/ops/config`）。
 *
 * <p>权限映射：读走 {@code ops:config:list}；配置项增删改与「一键生效」走
 * {@code ops:config:apply}；按历史一键恢复走 {@code ops:config:rollback}。
 * L3 类别（sshd）的生效/恢复必须在请求体里带上键入的关键字（如 {@code APPLY sshd}）。
 *
 * <p>「一键生效」是本模块唯一的破坏性入口，其安全逻辑全部收敛在
 * {@link ServerConfigService#apply}（9 道闸门），控制器只做鉴权与参数透传。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/config")
@RequiredArgsConstructor
public class ServerConfigController {

    private final ServerConfigService service;

    // ==================== 类别与探测 ====================

    /** 类别列表 + 宿主能力（可用性/托管路径/风险级） */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/categories")
    public R<List<ServerConfigCategoryVO>> categories() {
        return R.ok(service.categories());
    }

    /** 主动重探宿主能力（安装宿主代理后无需重启面板） */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/detect")
    public R<List<ServerConfigCategoryVO>> detect() {
        return R.ok(service.detect());
    }

    // ==================== 配置项 CRUD ====================

    /** 该类别配置项（托管值 + 当前生效值 + 推荐值） */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/category/{key}/items")
    public R<List<ServerConfigItemVO>> items(@PathVariable("key") String key) {
        return R.ok(service.items(key));
    }

    @Audit(module = "ops", action = "config:item:create")
    @SaCheckPermission("ops:config:apply")
    @PostMapping("/category/{key}/item")
    public R<ServerConfigItemVO> createItem(@PathVariable("key") String key,
                                            @RequestBody ServerConfigItemBody body) {
        return R.ok(service.createItem(key, body));
    }

    @Audit(module = "ops", action = "config:item:update")
    @SaCheckPermission("ops:config:apply")
    @PutMapping("/category/{key}/item/{itemKey}")
    public R<ServerConfigItemVO> updateItem(@PathVariable("key") String key,
                                            @PathVariable("itemKey") String itemKey,
                                            @RequestBody ServerConfigItemBody body) {
        return R.ok(service.updateItem(key, itemKey, body));
    }

    @Audit(module = "ops", action = "config:item:delete")
    @SaCheckPermission("ops:config:apply")
    @DeleteMapping("/category/{key}/item/{itemKey}")
    public R<Void> deleteItem(@PathVariable("key") String key,
                              @PathVariable("itemKey") String itemKey) {
        service.deleteItem(key, itemKey);
        return R.ok();
    }

    // ==================== 预演 与 一键生效 ====================

    /** 预演：渲染全文 + diff + 宿主机 dry-run 校验（只读，不落盘） */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/category/{key}/preview")
    public R<ServerConfigPreviewVO> preview(@PathVariable("key") String key) {
        return R.ok(service.preview(key));
    }

    /** 一键生效（9 道闸门；L3 类别需 confirm 键入关键字） */
    @Audit(module = "ops", action = "config:apply", risky = true)
    @SaCheckPermission("ops:config:apply")
    @PostMapping("/category/{key}/apply")
    public R<ServerConfigApplyVO> apply(@PathVariable("key") String key,
                                        @RequestBody(required = false) ServerConfigActionBody body) {
        return R.ok(service.apply(key, body == null ? null : body.getConfirm()));
    }

    /**
     * 停止托管：删除该类别写入的托管片段及其备份，使发行版原配置重新生效（设计承诺的「纯净卸载」）。
     *
     * <p>与生效同属破坏性入口（会触发 reload/restart），因此同样按 L3 规则要求键入关键字。
     * 配置项文档保留在 MongoDB 中，不会丢失用户录入的内容。
     */
    @Audit(module = "ops", action = "config:unmanage", risky = true)
    @SaCheckPermission("ops:config:apply")
    @PostMapping("/category/{key}/unmanage")
    public R<ServerConfigApplyVO> unmanage(@PathVariable("key") String key,
                                           @RequestBody(required = false) ServerConfigActionBody body) {
        return R.ok(service.unmanage(key, body == null ? null : body.getConfirm()));
    }

    // ==================== 历史 与 恢复 ====================

    /**
     * 变更历史分页。
     *
     * @param key 类别键；传 {@code all} 或留空表示不按类别过滤（跨类别总览）
     */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/category/{key}/change/page")
    public R<PageResult<ServerConfigChangeVO>> changePage(@PathVariable("key") String key,
                                                          @RequestParam(defaultValue = "1") int pageNum,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        String filter = key == null || key.isBlank() || "all".equalsIgnoreCase(key) ? null : key;
        return R.ok(service.changePage(filter, pageNum, pageSize));
    }

    /** 单条历史详情（前后全文 + diff + 校验/生效输出 + 配置项快照） */
    @SaCheckPermission("ops:config:list")
    @GetMapping("/change/{id}")
    public R<ServerConfigChangeVO> changeDetail(@PathVariable("id") String id) {
        return R.ok(service.changeDetail(id));
    }

    /** 按历史一键恢复（走与生效同一套校验链；L3 类别需 confirm 键入关键字） */
    @Audit(module = "ops", action = "config:restore", risky = true)
    @SaCheckPermission("ops:config:rollback")
    @PostMapping("/change/{id}/restore")
    public R<ServerConfigApplyVO> restore(@PathVariable("id") String id,
                                          @RequestBody(required = false) ServerConfigActionBody body) {
        return R.ok(service.restore(id,
                body == null ? null : body.getTarget(),
                body == null ? null : body.getConfirm()));
    }
}
