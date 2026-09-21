package com.serverpanel.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.ops.dto.NginxActionResultVO;
import com.serverpanel.ops.dto.NginxCertBody;
import com.serverpanel.ops.dto.NginxInstanceBody;
import com.serverpanel.ops.dto.NginxSiteBody;
import com.serverpanel.ops.dto.NginxStatusVO;
import com.serverpanel.ops.dto.NginxStreamBody;
import com.serverpanel.ops.dto.NginxUpstreamBody;
import com.serverpanel.ops.entity.OpsNginxCert;
import com.serverpanel.ops.entity.OpsNginxChange;
import com.serverpanel.ops.entity.OpsNginxInstance;
import com.serverpanel.ops.entity.OpsNginxSite;
import com.serverpanel.ops.entity.OpsNginxStream;
import com.serverpanel.ops.entity.OpsNginxUpstream;
import com.serverpanel.ops.service.NginxInstanceService;
import com.serverpanel.ops.service.NginxService;
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
import java.util.Map;

/**
 * Nginx 管理接口。
 *
 * <p>权限映射：读走 {@code ops:nginx:list}；站点/上游写走 {@code ops:nginx:site:write}；
 * 证书走 {@code ops:nginx:cert}；重载与校验走 {@code ops:nginx:reload}；回滚走
 * {@code ops:nginx:rollback}；实例配置走 {@code ops:nginx:instance}；四层转发走
 * {@code ops:nginx:stream}。危险操作（删站/删实例/删证书/删转发/回滚）需二次确认。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@RestController
@RequestMapping("/api/v1/ops/nginx")
@RequiredArgsConstructor
public class NginxController {

    private final NginxService nginxService;
    private final NginxInstanceService instanceService;

    // ==================== 实例 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/instance/list")
    public R<List<OpsNginxInstance>> instances() {
        return R.ok(instanceService.list());
    }

    /** 探测主机 nginx（不落库，供「探测」按钮展示） */
    @SaCheckPermission("ops:nginx:instance")
    @GetMapping("/instance/detect")
    public R<NginxInstanceBody> detect() {
        return R.ok(instanceService.probe());
    }

    @Audit(module = "ops", action = "nginx:instance:save")
    @SaCheckPermission("ops:nginx:instance")
    @PostMapping("/instance")
    public R<OpsNginxInstance> saveInstance(@RequestBody NginxInstanceBody body) {
        return R.ok(instanceService.save(body));
    }

    @Audit(module = "ops", action = "nginx:instance:save")
    @SaCheckPermission("ops:nginx:instance")
    @PutMapping("/instance")
    public R<OpsNginxInstance> updateInstance(@RequestBody NginxInstanceBody body) {
        return R.ok(instanceService.save(body));
    }

    @Audit(module = "ops", action = "nginx:instance:delete", risky = true)
    @SaCheckPermission("ops:nginx:instance")
    @DeleteMapping("/instance/{id}")
    public R<Void> deleteInstance(@PathVariable Long id) {
        instanceService.delete(id);
        return R.ok();
    }

    @Audit(module = "ops", action = "nginx:instance:default")
    @SaCheckPermission("ops:nginx:instance")
    @PutMapping("/instance/{id}/default")
    public R<Void> setDefaultInstance(@PathVariable Long id) {
        instanceService.setDefault(id);
        return R.ok();
    }

    // ==================== 状态 / 现有站点 / 预览 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/status")
    public R<NginxStatusVO> status(@RequestParam(required = false) Long instanceId) {
        return R.ok(nginxService.status(instanceId));
    }

    /** 宝塔既有 vhost 站点（只读） */
    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/existing")
    public R<List<Map<String, Object>>> existing(@RequestParam(required = false) Long instanceId) {
        return R.ok(nginxService.existing(instanceId));
    }

    /** 渲染预览（不落盘） */
    @SaCheckPermission("ops:nginx:site:write")
    @PostMapping("/site/preview")
    public R<String> previewSite(@RequestBody NginxSiteBody body) {
        return R.ok(nginxService.previewSite(body));
    }

    // ==================== 站点 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/site/page")
    public R<PageResult<OpsNginxSite>> sitePage(
            @RequestParam(required = false) Long instanceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(nginxService.sitePage(instanceId, pageNum, pageSize, keyword));
    }

    @Audit(module = "ops", action = "nginx:site:create", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @PostMapping("/site")
    public R<NginxActionResultVO> createSite(@RequestBody NginxSiteBody body) {
        return R.ok(nginxService.createSite(body));
    }

    @Audit(module = "ops", action = "nginx:site:update", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @PutMapping("/site")
    public R<NginxActionResultVO> updateSite(@RequestBody NginxSiteBody body) {
        return R.ok(nginxService.updateSite(body));
    }

    @Audit(module = "ops", action = "nginx:site:delete", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @DeleteMapping("/site/{id}")
    public R<NginxActionResultVO> deleteSite(@PathVariable Long id,
                                             @RequestBody(required = false) ConfirmBody body) {
        return R.ok(nginxService.deleteSite(id, body == null ? null : body.getConfirm()));
    }

    @Audit(module = "ops", action = "nginx:site:toggle", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @PutMapping("/site/{id}/status/{status}")
    public R<NginxActionResultVO> toggleSite(@PathVariable Long id, @PathVariable Integer status) {
        return R.ok(nginxService.toggleSite(id, status));
    }

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/site/{id}/conf")
    public R<String> siteConf(@PathVariable Long id) {
        return R.ok(nginxService.siteConf(id));
    }

    // ==================== 上游组 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/upstream/page")
    public R<PageResult<OpsNginxUpstream>> upstreamPage(
            @RequestParam(required = false) Long instanceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(nginxService.upstreamPage(instanceId, pageNum, pageSize));
    }

    @Audit(module = "ops", action = "nginx:upstream:save", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @PostMapping("/upstream")
    public R<NginxActionResultVO> saveUpstream(@RequestBody NginxUpstreamBody body) {
        return R.ok(nginxService.saveUpstream(body));
    }

    @Audit(module = "ops", action = "nginx:upstream:save", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @PutMapping("/upstream")
    public R<NginxActionResultVO> updateUpstream(@RequestBody NginxUpstreamBody body) {
        return R.ok(nginxService.saveUpstream(body));
    }

    @Audit(module = "ops", action = "nginx:upstream:delete", risky = true)
    @SaCheckPermission("ops:nginx:site:write")
    @DeleteMapping("/upstream/{id}")
    public R<NginxActionResultVO> deleteUpstream(@PathVariable Long id,
                                                 @RequestBody(required = false) ConfirmBody body) {
        return R.ok(nginxService.deleteUpstream(id, body == null ? null : body.getConfirm()));
    }

    // ==================== 四层转发 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/stream/page")
    public R<PageResult<OpsNginxStream>> streamPage(
            @RequestParam(required = false) Long instanceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(nginxService.streamPage(instanceId, pageNum, pageSize));
    }

    @Audit(module = "ops", action = "nginx:stream:save", risky = true)
    @SaCheckPermission("ops:nginx:stream")
    @PostMapping("/stream")
    public R<NginxActionResultVO> saveStream(@RequestBody NginxStreamBody body) {
        return R.ok(nginxService.saveStream(body));
    }

    @Audit(module = "ops", action = "nginx:stream:save", risky = true)
    @SaCheckPermission("ops:nginx:stream")
    @PutMapping("/stream")
    public R<NginxActionResultVO> updateStream(@RequestBody NginxStreamBody body) {
        return R.ok(nginxService.saveStream(body));
    }

    @Audit(module = "ops", action = "nginx:stream:delete", risky = true)
    @SaCheckPermission("ops:nginx:stream")
    @DeleteMapping("/stream/{id}")
    public R<NginxActionResultVO> deleteStream(@PathVariable Long id,
                                               @RequestBody(required = false) ConfirmBody body) {
        return R.ok(nginxService.deleteStream(id, body == null ? null : body.getConfirm()));
    }

    @Audit(module = "ops", action = "nginx:stream:toggle", risky = true)
    @SaCheckPermission("ops:nginx:stream")
    @PutMapping("/stream/{id}/status/{status}")
    public R<NginxActionResultVO> toggleStream(@PathVariable Long id, @PathVariable Integer status) {
        return R.ok(nginxService.toggleStream(id, status));
    }

    // ==================== 证书 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/cert/page")
    public R<PageResult<OpsNginxCert>> certPage(
            @RequestParam(required = false) Long instanceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(nginxService.certPage(instanceId, pageNum, pageSize));
    }

    @Audit(module = "ops", action = "nginx:cert:issue", risky = true)
    @SaCheckPermission("ops:nginx:cert")
    @PostMapping("/cert/issue")
    public R<NginxActionResultVO> issueCert(@RequestBody NginxCertBody body) {
        return R.ok(nginxService.issueCert(body));
    }

    @Audit(module = "ops", action = "nginx:cert:renew", risky = true)
    @SaCheckPermission("ops:nginx:cert")
    @PostMapping("/cert/{id}/renew")
    public R<NginxActionResultVO> renewCert(@PathVariable Long id) {
        return R.ok(nginxService.renewCert(id));
    }

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/cert/{id}/status")
    public R<OpsNginxCert> certStatus(@PathVariable Long id) {
        return R.ok(nginxService.certStatus(id));
    }

    @Audit(module = "ops", action = "nginx:cert:upload", risky = true)
    @SaCheckPermission("ops:nginx:cert")
    @PostMapping("/cert/upload")
    public R<NginxActionResultVO> uploadCert(@RequestBody NginxCertBody body) {
        return R.ok(nginxService.uploadCert(body));
    }

    @Audit(module = "ops", action = "nginx:cert:delete", risky = true)
    @SaCheckPermission("ops:nginx:cert")
    @DeleteMapping("/cert/{id}")
    public R<NginxActionResultVO> deleteCert(@PathVariable Long id,
                                             @RequestBody(required = false) ConfirmBody body) {
        return R.ok(nginxService.deleteCert(id, body == null ? null : body.getConfirm()));
    }

    // ==================== 日志 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/log/list")
    public R<List<Map<String, Object>>> logList(@RequestParam(required = false) Long instanceId) {
        return R.ok(nginxService.logList(instanceId));
    }

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/log/tail")
    public R<List<String>> logTail(
            @RequestParam(required = false) Long instanceId,
            @RequestParam String file,
            @RequestParam(defaultValue = "200") int lines) {
        return R.ok(nginxService.logTail(instanceId, file, lines));
    }

    // ==================== 变更历史与回滚 ====================

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/change/page")
    public R<PageResult<OpsNginxChange>> changePage(
            @RequestParam(required = false) Long instanceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(nginxService.changePage(instanceId, pageNum, pageSize));
    }

    @SaCheckPermission("ops:nginx:list")
    @GetMapping("/change/{id}")
    public R<OpsNginxChange> changeDetail(@PathVariable Long id) {
        return R.ok(nginxService.changeDetail(id));
    }

    @Audit(module = "ops", action = "nginx:rollback", risky = true)
    @SaCheckPermission("ops:nginx:rollback")
    @PostMapping("/change/{id}/rollback")
    public R<NginxActionResultVO> rollback(@PathVariable Long id,
                                           @RequestBody(required = false) ConfirmBody body) {
        return R.ok(nginxService.rollback(id, body == null ? null : body.getConfirm()));
    }

    // ==================== 运维动作 ====================

    @Audit(module = "ops", action = "nginx:reload", risky = true)
    @SaCheckPermission("ops:nginx:reload")
    @PostMapping("/reload")
    public R<NginxActionResultVO> reload() {
        return R.ok(nginxService.reload());
    }

    @SaCheckPermission("ops:nginx:reload")
    @PostMapping("/test")
    public R<NginxActionResultVO> test() {
        return R.ok(nginxService.test());
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
}
