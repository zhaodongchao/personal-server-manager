package com.serverpanel.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.ops.dto.NginxInstanceBody;
import com.serverpanel.ops.entity.OpsNginxInstance;
import com.serverpanel.ops.mapper.OpsNginxInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Nginx 实例管理：可显式指定要管理的 nginx，未指定时自动探测主机默认 nginx。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NginxInstanceService {

    private final OpsNginxInstanceMapper instanceMapper;

    private final HostChannelService hostChannel;

    public List<OpsNginxInstance> list() {
        return instanceMapper.selectList(new LambdaQueryWrapper<OpsNginxInstance>()
                .orderByDesc(OpsNginxInstance::getDefaultFlag)
                .orderByAsc(OpsNginxInstance::getId));
    }

    public OpsNginxInstance require(Long id) {
        OpsNginxInstance inst = id == null ? null : instanceMapper.selectById(id);
        if (inst == null) {
            throw new ServiceException(ErrorCode.NGINX_INSTANCE_NOT_FOUND);
        }
        return inst;
    }

    /** 取默认实例（不触发自动探测，无则返回 null） */
    public OpsNginxInstance findDefault() {
        return instanceMapper.selectOne(new LambdaQueryWrapper<OpsNginxInstance>()
                .eq(OpsNginxInstance::getDefaultFlag, 1)
                .last("LIMIT 1"));
    }

    /** 取默认实例；没有则自动探测主机 nginx 并落库 */
    public OpsNginxInstance requireDefault() {
        OpsNginxInstance def = findDefault();
        return def != null ? def : ensureFromDetect();
    }

    /** 自动探测主机 nginx（不落库，供「探测」按钮展示） */
    public NginxInstanceBody probe() {
        return mapDetect(hostChannel.call("nginx.detect", Map.of(), "探测主机 Nginx"));
    }

    /** 自动探测并落库为默认实例 */
    public OpsNginxInstance ensureFromDetect() {
        NginxInstanceBody body = mapDetect(hostChannel.call("nginx.detect", Map.of(), "探测主机 Nginx"));
        OpsNginxInstance inst = new OpsNginxInstance();
        inst.setName("主机默认 nginx");
        inst.setDetectMode("auto");
        inst.setBinaryPath(body.getBinaryPath());
        inst.setPrefix(body.getPrefix());
        inst.setConfPath(body.getConfPath());
        inst.setManagedDir(body.getManagedDir());
        inst.setStreamDir(body.getStreamDir());
        inst.setCertDir(body.getCertDir());
        inst.setAcmeWebroot(body.getAcmeWebroot());
        inst.setLogDir(body.getLogDir());
        inst.setDefaultFlag(1);
        inst.setStatus(1);
        inst.setCreatedAt(LocalDateTime.now());
        instanceMapper.insert(inst);
        log.info("自动创建默认 Nginx 实例 id={} conf={}", inst.getId(), inst.getConfPath());
        return inst;
    }

    /** 新建或更新实例 */
    public OpsNginxInstance save(NginxInstanceBody body) {
        OpsNginxInstance inst;
        if (body.getId() != null) {
            inst = require(body.getId());
        } else {
            inst = new OpsNginxInstance();
            inst.setCreatedAt(LocalDateTime.now());
        }
        if (body.getName() != null) {
            inst.setName(body.getName().trim());
        }
        inst.setDetectMode(body.getDetectMode() == null ? "manual" : body.getDetectMode());
        inst.setBinaryPath(body.getBinaryPath());
        inst.setPrefix(body.getPrefix());
        inst.setConfPath(body.getConfPath());
        inst.setManagedDir(body.getManagedDir());
        inst.setStreamDir(body.getStreamDir());
        inst.setCertDir(body.getCertDir());
        inst.setAcmeWebroot(body.getAcmeWebroot());
        inst.setLogDir(body.getLogDir());
        inst.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        inst.setRemark(body.getRemark());
        if (body.getId() == null) {
            instanceMapper.insert(inst);
        } else {
            instanceMapper.updateById(inst);
        }
        return inst;
    }

    /** 删除实例（默认实例不可删，须先指定其它默认） */
    public void delete(Long id) {
        OpsNginxInstance inst = require(id);
        if (inst.getDefaultFlag() != null && inst.getDefaultFlag() == 1) {
            throw new ServiceException(ErrorCode.NGINX_GUARD_TRIGGERED.getCode(),
                    "默认实例不可直接删除，请先将其它实例设为默认");
        }
        instanceMapper.deleteById(id);
    }

    /** 设为默认实例 */
    public void setDefault(Long id) {
        OpsNginxInstance inst = require(id);
        instanceMapper.update(null, new LambdaUpdateWrapper<OpsNginxInstance>()
                .eq(OpsNginxInstance::getDefaultFlag, 1)
                .set(OpsNginxInstance::getDefaultFlag, 0));
        inst.setDefaultFlag(1);
        instanceMapper.updateById(inst);
    }

    private NginxInstanceBody mapDetect(HostResult r) {
        Map<String, Object> d = r.getData();
        boolean found = d != null && Boolean.TRUE.equals(d.get("found"));
        if (!found) {
            throw new ServiceException(ErrorCode.NGINX_UNAVAILABLE.getCode(),
                    "宿主机未检测到 nginx，请先在宿主机安装并重试");
        }
        String prefix = r.dataString("prefix");
        String managedDir = prefix == null ? null : prefix + "/conf/serverpanel.d";
        NginxInstanceBody body = new NginxInstanceBody();
        body.setDetectMode("auto");
        body.setBinaryPath(r.dataString("binary"));
        body.setPrefix(prefix);
        body.setConfPath(r.dataString("confPath"));
        body.setManagedDir(managedDir);
        body.setStreamDir(managedDir == null ? null : managedDir + "/stream");
        body.setCertDir(managedDir == null ? null : managedDir + "/certs");
        body.setAcmeWebroot("/www/wwwroot/psm-acme");
        body.setLogDir("/www/wwwlogs");
        return body;
    }
}
