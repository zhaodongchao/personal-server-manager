package com.serverpanel.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.ops.dto.NginxActionResultVO;
import com.serverpanel.ops.dto.NginxCertBody;
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
import com.serverpanel.ops.mapper.OpsNginxCertMapper;
import com.serverpanel.ops.mapper.OpsNginxChangeMapper;
import com.serverpanel.ops.mapper.OpsNginxSiteMapper;
import com.serverpanel.ops.mapper.OpsNginxStreamMapper;
import com.serverpanel.ops.mapper.OpsNginxUpstreamMapper;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nginx 管理（静态配置生成型，参照 NPM / Nginx UI / NginxWebUI）。
 *
 * <p>闭环：Web UI 录入意图 → 存库 → FreeMarker 渲染 → 写托管目录
 * → {@code nginx -t} 语法校验 → {@code nginx -s reload}（SIGHUP）生效；失败自动回滚。
 *
 * <p>通道分工（与设计 D2 一致）：
 * <ul>
 *   <li><b>写盘</b>：容器经 {@code /www} 挂载直写托管目录（managed_dir）；</li>
 *   <li><b>nginx -t / reload / certbot</b>：经 psm-hostagent（root）执行，规避宝塔 nginx
 *       logs 目录 700 root 的权限墙。</li>
 * </ul>
 *
 * <p>护栏：
 * <ol>
 *   <li>每次写盘前确保主配置 include 了托管目录（幂等自愈，备份 + {@code -t} 校验）；</li>
 *   <li>写盘三阶：备份 → 写新文件 → {@code -t} → 失败回滚 → 成功 reload；</li>
 *   <li>上游组 / 证书有引用计数保护；域名 / 四层端口有冲突检测；</li>
 *   <li>每次变更落快照，支持 diff 展示与一键回滚。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NginxService {

    private static final Configuration FTL = new Configuration(Configuration.VERSION_2_3_33);

    /** 域名（允许 *. 通配，供 DNS-01 通配证书站点使用） */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)(\\*\\.)?[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$");

    /** 站点/上游组/转发名：作为配置文件名与 nginx 标识，从严限制字符集 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$");

    private static final Set<String> SITE_TYPES = Set.of("proxy", "static");
    private static final Set<String> SSL_MODES = Set.of("off", "letsencrypt", "custom");
    private static final Set<String> STRATEGIES = Set.of("round_robin", "least_conn", "ip_hash");
    private static final Set<String> LOC_TYPES = Set.of("proxy", "static", "redirect", "deny");
    private static final Set<String> STREAM_PROTOCOLS = Set.of("tcp", "udp");

    static {
        FTL.setTemplateLoader(new ClassTemplateLoader(NginxService.class.getClassLoader(), "templates"));
        FTL.setDefaultEncoding(StandardCharsets.UTF_8.name());
    }

    private final OpsNginxSiteMapper siteMapper;
    private final OpsNginxUpstreamMapper upstreamMapper;
    private final OpsNginxStreamMapper streamMapper;
    private final OpsNginxCertMapper certMapper;
    private final OpsNginxChangeMapper changeMapper;
    private final NginxInstanceService instanceService;
    private final HostChannelService hostChannel;
    private final ObjectMapper objectMapper;

    // ==================== 实例解析 ====================

    /** 未指定实例则回退到默认实例 */
    private OpsNginxInstance resolveInstance(Long instanceId) {
        if (instanceId != null) {
            return instanceService.require(instanceId);
        }
        return instanceService.requireDefault();
    }

    // ==================== 状态 ====================

    /** 当前实例运行态 + 版本 + 能力 + 各类资源计数 */
    public NginxStatusVO status(Long instanceId) {
        NginxStatusVO vo = new NginxStatusVO();
        boolean channelOk;
        try {
            channelOk = hostChannel.available();
            vo.setChannelOk(channelOk);
        } catch (RuntimeException e) {
            vo.setChannelOk(false);
            vo.setChannelMessage(e.getMessage());
            return vo;
        }
        if (!channelOk) {
            vo.setChannelMessage("宿主执行通道不可用，请先安装 psm-hostagent");
            return vo;
        }
        OpsNginxInstance inst = null;
        if (instanceId != null) {
            try {
                inst = instanceService.require(instanceId);
            } catch (RuntimeException ignored) {
                // 实例不存在，由 instanceExists=false 表达
            }
        } else {
            try {
                inst = instanceService.findDefault();
            } catch (RuntimeException ignored) {
                // 无默认实例
            }
        }
        if (inst == null) {
            vo.setInstanceExists(false);
            return vo;
        }
        vo.setInstanceExists(true);
        vo.setInstanceId(inst.getId());
        vo.setInstanceName(inst.getName());
        vo.setNginxBinary(inst.getBinaryPath());
        vo.setConfPath(inst.getConfPath());
        try {
            HostResult r = hostChannel.call("nginx.detect", Map.of(), "探测 Nginx", 30);
            if (r.dataBool("found")) {
                vo.setNginxAvailable(true);
                vo.setNginxVersion(r.dataString("version"));
                vo.setCertbotVersion(r.dataString("certbotVersion"));
            } else {
                vo.setNginxAvailable(false);
                vo.setConfigMessage("宿主机未检测到 nginx");
            }
        } catch (RuntimeException e) {
            vo.setNginxAvailable(false);
            vo.setConfigMessage(e.getMessage());
        }
        try {
            HostResult t = hostChannel.call("nginx.test",
                    testArgs(inst), "校验 nginx 配置", 60);
            vo.setConfigValid(t.isSuccess());
            if (!t.isSuccess()) {
                vo.setConfigMessage(t.errorText());
            }
        } catch (RuntimeException e) {
            vo.setConfigValid(false);
            if (vo.getConfigMessage() == null) {
                vo.setConfigMessage(e.getMessage());
            }
        }
        vo.setSiteCount(siteMapper.selectCount(new LambdaQueryWrapper<OpsNginxSite>()
                .eq(OpsNginxSite::getInstanceId, inst.getId())));
        vo.setUpstreamCount(upstreamMapper.selectCount(new LambdaQueryWrapper<OpsNginxUpstream>()
                .eq(OpsNginxUpstream::getInstanceId, inst.getId())));
        vo.setStreamCount(streamMapper.selectCount(new LambdaQueryWrapper<OpsNginxStream>()
                .eq(OpsNginxStream::getInstanceId, inst.getId())));
        vo.setCertCount(certMapper.selectCount(new LambdaQueryWrapper<OpsNginxCert>()
                .eq(OpsNginxCert::getInstanceId, inst.getId())));

        LocalDateTime now = LocalDateTime.now();
        List<String> expiring = new ArrayList<>();
        for (OpsNginxCert c : certMapper.selectList(new LambdaQueryWrapper<OpsNginxCert>()
                .eq(OpsNginxCert::getInstanceId, inst.getId()))) {
            if (c.getNotAfter() != null && c.getNotAfter().isAfter(now)
                    && c.getNotAfter().isBefore(now.plusDays(30))) {
                expiring.add(c.getDomain());
            }
        }
        vo.setExpiringCerts(expiring);
        return vo;
    }

    // ==================== 站点 ====================

    public PageResult<OpsNginxSite> sitePage(Long instanceId, int pageNum, int pageSize, String keyword) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        Page<OpsNginxSite> page = siteMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsNginxSite>()
                        .eq(OpsNginxSite::getInstanceId, inst.getId())
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(OpsNginxSite::getName, keyword)
                                .or().like(OpsNginxSite::getDomains, keyword))
                        .orderByAsc(OpsNginxSite::getName));
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    public NginxActionResultVO createSite(NginxSiteBody body) {
        OpsNginxInstance inst = resolveInstance(body == null ? null : body.getInstanceId());
        OpsNginxSite site = buildValidatedSite(inst, body);
        site.setInstanceId(inst.getId());
        if (siteMapper.selectCount(new LambdaQueryWrapper<OpsNginxSite>()
                .eq(OpsNginxSite::getInstanceId, inst.getId())
                .eq(OpsNginxSite::getName, site.getName())) > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点名已存在");
        }
        checkDomainConflict(inst, null, parseDomains(site.getDomains()));
        String content = renderSite(site, inst);
        String confPath = siteConfPath(inst, site.getName());
        ensureIncludes(inst);
        String previous = readFile(confPath);
        applyConfig(inst, confPath, content, previous);
        site.setConfPath(confPath);
        site.setConfHash(sha256(content));
        site.setStatus(1);
        site.setCreatedAt(LocalDateTime.now());
        siteMapper.insert(site);
        Long changeId = recordChange(inst.getId(), "CREATE_SITE", "site", site.getId(), confPath,
                previous == null ? "" : previous, content, previous == null ? "" : previous);
        return result("站点「" + site.getName() + "」已创建并生效", changeId);
    }

    public NginxActionResultVO updateSite(NginxSiteBody body) {
        if (body == null || body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少站点 ID");
        }
        OpsNginxSite db = siteMapper.selectById(body.getId());
        if (db == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点不存在");
        }
        OpsNginxInstance inst = resolveInstance(db.getInstanceId());
        OpsNginxSite site = buildValidatedSite(inst, body);
        site.setId(db.getId());
        site.setInstanceId(inst.getId());
        // 站点名即配置文件名与 nginx 标识，v1 不允许改名
        if (!db.getName().equals(site.getName())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点名不可修改（如需改名请删除后重建）");
        }
        checkDomainConflict(inst, db.getId(), parseDomains(site.getDomains()));
        String content = renderSite(site, inst);
        String confPath = siteConfPath(inst, site.getName());
        ensureIncludes(inst);
        String previous = readFile(confPath);
        applyConfig(inst, confPath, content, previous);
        site.setConfPath(confPath);
        site.setConfHash(sha256(content));
        site.setStatus(db.getStatus() == null ? 1 : db.getStatus());
        site.setCreatedAt(db.getCreatedAt());
        siteMapper.updateById(site);
        Long changeId = recordChange(inst.getId(), "UPDATE_SITE", "site", site.getId(), confPath,
                previous == null ? "" : previous, content, previous == null ? "" : previous);
        return result("站点「" + site.getName() + "」已更新并生效", changeId);
    }

    public NginxActionResultVO deleteSite(Long id, String confirm) {
        OpsNginxSite db = requireSite(id);
        OpsNginxInstance inst = resolveInstance(db.getInstanceId());
        requireConfirm(confirm, db.getName(),
                "删除站点「" + db.getName() + "」将立即移除其反代/静态服务，需二次确认");
        String confPath = db.getConfPath() == null ? siteConfPath(inst, db.getName()) : db.getConfPath();
        String previous = readFile(confPath);
        applyDelete(inst, confPath, previous);
        siteMapper.deleteById(id);
        Long changeId = recordChange(inst.getId(), "DELETE_SITE", "site", id, confPath,
                previous == null ? "" : previous, "", previous == null ? "" : previous);
        return result("站点「" + db.getName() + "」已删除", changeId);
    }

    public NginxActionResultVO toggleSite(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "状态仅支持 0/1");
        }
        OpsNginxSite db = requireSite(id);
        OpsNginxInstance inst = resolveInstance(db.getInstanceId());
        String confPath = db.getConfPath() == null ? siteConfPath(inst, db.getName()) : db.getConfPath();
        String previous = readFile(confPath);
        if (status == 1) {
            String content = renderSite(db, inst);
            ensureIncludes(inst);
            applyConfig(inst, confPath, content, previous);
        } else {
            applyDelete(inst, confPath, previous);
        }
        db.setStatus(status);
        siteMapper.updateById(db);
        Long changeId = recordChange(inst.getId(), "TOGGLE", "site", id, confPath,
                previous == null ? "" : previous,
                status == 1 ? readFile(confPath) : "", previous == null ? "" : previous);
        return result(status == 1 ? "站点「" + db.getName() + "」已启用" : "站点「" + db.getName() + "」已停用", changeId);
    }

    /** 站点配置文件原文（在线查看） */
    public String siteConf(Long id) {
        OpsNginxSite db = requireSite(id);
        String content = readFile(db.getConfPath());
        return content == null ? "" : content;
    }

    /** 渲染预览（不落盘、不生效） */
    public String previewSite(NginxSiteBody body) {
        OpsNginxInstance inst = resolveInstance(body == null ? null : body.getInstanceId());
        OpsNginxSite site = buildValidatedSite(inst, body);
        return renderSite(site, inst);
    }

    private OpsNginxSite requireSite(Long id) {
        OpsNginxSite db = siteMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点不存在");
        }
        return db;
    }

    /** 校验并构建站点实体（不落库） */
    private OpsNginxSite buildValidatedSite(OpsNginxInstance inst, NginxSiteBody body) {
        if (body == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String name = body.getName() == null ? "" : body.getName().trim();
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点名仅支持字母/数字/-/_，且以字母或数字开头");
        }
        List<String> domains = body.getDomains() == null ? List.of() : body.getDomains();
        if (domains.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "至少填写一个域名");
        }
        for (String d : domains) {
            if (d == null || !DOMAIN.matcher(d.trim()).matches()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "域名非法: " + d);
            }
        }
        String siteType = body.getSiteType() == null ? "proxy" : body.getSiteType();
        if (!SITE_TYPES.contains(siteType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "站点类型仅支持 proxy/static");
        }
        String sslMode = body.getSslMode() == null ? "off" : body.getSslMode();
        if (!SSL_MODES.contains(sslMode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "SSL 模式仅支持 off/letsencrypt/custom");
        }

        OpsNginxSite site = new OpsNginxSite();
        site.setName(name);
        site.setDomains(String.join(",", domains.stream().map(String::trim).toList()));
        site.setSiteType(siteType);
        site.setSslMode(sslMode);
        site.setHttpRedirect(body.getHttpRedirect() == null || body.getHttpRedirect() ? 1 : 0);
        site.setHsts(body.getHsts() != null && body.getHsts() ? 1 : 0);
        site.setRemark(body.getRemark());

        if ("proxy".equals(siteType)) {
            if (body.getUpstreamId() == null
                    && (body.getUpstreamInline() == null || body.getUpstreamInline().isBlank())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "反代站点必须指定上游组或内联上游");
            }
            if (body.getUpstreamId() != null) {
                OpsNginxUpstream up = upstreamMapper.selectById(body.getUpstreamId());
                if (up == null || !up.getInstanceId().equals(inst.getId())) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组不存在或不属于当前实例");
                }
                site.setUpstreamId(body.getUpstreamId());
            } else {
                site.setUpstreamInline(body.getUpstreamInline().trim());
            }
        } else {
            if (body.getStaticRoot() == null || body.getStaticRoot().isBlank()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "静态站点必须指定站点根目录");
            }
            site.setStaticRoot(body.getStaticRoot().trim());
        }

        if (!"off".equals(sslMode)) {
            if (body.getCertId() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "开启 HTTPS 必须选择证书");
            }
            OpsNginxCert cert = certMapper.selectById(body.getCertId());
            if (cert == null || !cert.getInstanceId().equals(inst.getId())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "证书不存在或不属于当前实例");
            }
            site.setCertId(body.getCertId());
        }

        List<NginxSiteBody.NginxLocation> locations = validateLocations(body.getLocations());
        site.setLocationsJson(serialize(locations));
        return site;
    }

    private List<NginxSiteBody.NginxLocation> validateLocations(List<NginxSiteBody.NginxLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return List.of();
        }
        List<NginxSiteBody.NginxLocation> out = new ArrayList<>();
        for (NginxSiteBody.NginxLocation loc : locations) {
            if (loc == null) {
                continue;
            }
            String path = loc.getPath() == null ? "" : loc.getPath().trim();
            String type = loc.getType() == null ? "" : loc.getType().trim();
            if (path.isEmpty() || !path.startsWith("/")) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "location 路径必须以 / 开头");
            }
            if (!LOC_TYPES.contains(type)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "location 类型仅支持 proxy/static/redirect/deny");
            }
            if ("proxy".equals(type) && (loc.getUpstream() == null || loc.getUpstream().isBlank())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "proxy 类型 location 必须指定上游");
            }
            if ("static".equals(type) && (loc.getStaticRoot() == null || loc.getStaticRoot().isBlank())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "static 类型 location 必须指定根目录");
            }
            if ("redirect".equals(type) && (loc.getRedirectTarget() == null || loc.getRedirectTarget().isBlank())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "redirect 类型 location 必须指定目标 URL");
            }
            out.add(loc);
        }
        return out;
    }

    // ==================== 上游组 ====================

    public PageResult<OpsNginxUpstream> upstreamPage(Long instanceId, int pageNum, int pageSize) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        Page<OpsNginxUpstream> page = upstreamMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsNginxUpstream>()
                        .eq(OpsNginxUpstream::getInstanceId, inst.getId())
                        .orderByAsc(OpsNginxUpstream::getName));
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    public NginxActionResultVO saveUpstream(NginxUpstreamBody body) {
        if (body == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        OpsNginxInstance inst = resolveInstance(body.getInstanceId());
        String name = body.getName() == null ? "" : body.getName().trim();
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组名仅支持字母/数字/-/_，且以字母或数字开头");
        }
        String strategy = body.getStrategy() == null ? "round_robin" : body.getStrategy();
        if (!STRATEGIES.contains(strategy)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "负载策略仅支持 round_robin/least_conn/ip_hash");
        }
        List<NginxUpstreamBody.Server> servers = body.getServers() == null ? List.of() : body.getServers();
        if (servers.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "至少填写一个后端");
        }
        for (NginxUpstreamBody.Server s : servers) {
            if (s == null || s.getHost() == null || s.getHost().isBlank() || s.getPort() == null
                    || s.getPort() < 1 || s.getPort() > 65535) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "后端地址或端口非法");
            }
        }

        OpsNginxUpstream up;
        boolean creating = body.getId() == null;
        if (creating) {
            up = new OpsNginxUpstream();
            up.setInstanceId(inst.getId());
            up.setCreatedAt(LocalDateTime.now());
            if (upstreamMapper.selectCount(new LambdaQueryWrapper<OpsNginxUpstream>()
                    .eq(OpsNginxUpstream::getInstanceId, inst.getId())
                    .eq(OpsNginxUpstream::getName, name)) > 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组名已存在");
            }
        } else {
            up = upstreamMapper.selectById(body.getId());
            if (up == null || !up.getInstanceId().equals(inst.getId())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组不存在或不属于当前实例");
            }
            // 上游组名即 nginx upstream 标识与站点引用名，v1 不允许改名
            if (!up.getName().equals(name)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组名不可修改（如需改名请删除后重建）");
            }
        }
        up.setName(name);
        up.setStrategy(strategy);
        up.setServersJson(serialize(servers));
        up.setKeepalive(body.getKeepalive() == null ? 0 : body.getKeepalive());
        up.setRemark(body.getRemark());

        String content = renderUpstream(up);
        String confPath = upstreamConfPath(inst, name);
        ensureIncludes(inst);
        String previous = readFile(confPath);
        applyConfig(inst, confPath, content, previous);
        if (creating) {
            upstreamMapper.insert(up);
        } else {
            upstreamMapper.updateById(up);
        }
        Long changeId = recordChange(inst.getId(), creating ? "CREATE_UPSTREAM" : "UPDATE_UPSTREAM",
                "upstream", up.getId(), confPath,
                previous == null ? "" : previous, content, previous == null ? "" : previous);
        return result("上游组「" + name + "」已保存并生效", changeId);
    }

    public NginxActionResultVO deleteUpstream(Long id, String confirm) {
        OpsNginxUpstream up = upstreamMapper.selectById(id);
        if (up == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "上游组不存在");
        }
        OpsNginxInstance inst = resolveInstance(up.getInstanceId());
        long refs = siteMapper.selectCount(new LambdaQueryWrapper<OpsNginxSite>()
                .eq(OpsNginxSite::getInstanceId, inst.getId())
                .eq(OpsNginxSite::getUpstreamId, id));
        if (refs > 0) {
            throw new ServiceException(ErrorCode.NGINX_UPSTREAM_IN_USE);
        }
        requireConfirm(confirm, up.getName(),
                "删除上游组「" + up.getName() + "」需二次确认");
        String confPath = upstreamConfPath(inst, up.getName());
        String previous = readFile(confPath);
        applyDelete(inst, confPath, previous);
        upstreamMapper.deleteById(id);
        Long changeId = recordChange(inst.getId(), "DELETE_UPSTREAM", "upstream", id, confPath,
                previous == null ? "" : previous, "", previous == null ? "" : previous);
        return result("上游组「" + up.getName() + "」已删除", changeId);
    }

    // ==================== 四层转发（stream） ====================

    public PageResult<OpsNginxStream> streamPage(Long instanceId, int pageNum, int pageSize) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        Page<OpsNginxStream> page = streamMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsNginxStream>()
                        .eq(OpsNginxStream::getInstanceId, inst.getId())
                        .orderByAsc(OpsNginxStream::getListenPort));
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    public NginxActionResultVO saveStream(NginxStreamBody body) {
        if (body == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        OpsNginxInstance inst = resolveInstance(body.getInstanceId());
        String name = body.getName() == null ? "" : body.getName().trim();
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "转发名仅支持字母/数字/-/_，且以字母或数字开头");
        }
        String protocol = body.getProtocol() == null ? "tcp" : body.getProtocol();
        if (!STREAM_PROTOCOLS.contains(protocol)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "协议仅支持 tcp/udp");
        }
        if (body.getListenPort() == null || body.getListenPort() < 1 || body.getListenPort() > 65535) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "监听端口非法");
        }
        if (body.getUpstreamHost() == null || body.getUpstreamHost().isBlank()
                || body.getUpstreamPort() == null || body.getUpstreamPort() < 1 || body.getUpstreamPort() > 65535) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "目标地址或端口非法");
        }

        OpsNginxStream stream;
        boolean creating = body.getId() == null;
        if (creating) {
            stream = new OpsNginxStream();
            stream.setInstanceId(inst.getId());
            stream.setStatus(1);
            stream.setCreatedAt(LocalDateTime.now());
        } else {
            stream = streamMapper.selectById(body.getId());
            if (stream == null || !stream.getInstanceId().equals(inst.getId())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "转发不存在或不属于当前实例");
            }
            if (!stream.getName().equals(name)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "转发名不可修改（如需改名请删除后重建）");
            }
        }
        // 端口冲突（同协议、同端口、排除自身）
        long conflict = streamMapper.selectCount(new LambdaQueryWrapper<OpsNginxStream>()
                .eq(OpsNginxStream::getInstanceId, inst.getId())
                .eq(OpsNginxStream::getProtocol, protocol)
                .eq(OpsNginxStream::getListenPort, body.getListenPort())
                .ne(!creating, OpsNginxStream::getId, stream.getId()));
        if (conflict > 0) {
            throw new ServiceException(ErrorCode.NGINX_STREAM_PORT_CONFLICT);
        }

        stream.setName(name);
        stream.setProtocol(protocol);
        stream.setListenPort(body.getListenPort());
        stream.setUpstreamHost(body.getUpstreamHost().trim());
        stream.setUpstreamPort(body.getUpstreamPort());
        stream.setProxyTimeout(body.getProxyTimeout() == null ? 600 : body.getProxyTimeout());
        stream.setRemark(body.getRemark());

        String content = renderStream(stream);
        String confPath = streamConfPath(inst, name);
        ensureIncludes(inst);
        String previous = readFile(confPath);
        applyConfig(inst, confPath, content, previous);
        stream.setConfPath(confPath);
        stream.setConfHash(sha256(content));
        if (creating) {
            streamMapper.insert(stream);
        } else {
            streamMapper.updateById(stream);
        }
        Long changeId = recordChange(inst.getId(), creating ? "CREATE_STREAM" : "UPDATE_STREAM",
                "stream", stream.getId(), confPath,
                previous == null ? "" : previous, content, previous == null ? "" : previous);
        return result("四层转发「" + name + "」已保存并生效", changeId);
    }

    public NginxActionResultVO deleteStream(Long id, String confirm) {
        OpsNginxStream stream = streamMapper.selectById(id);
        if (stream == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "转发不存在");
        }
        OpsNginxInstance inst = resolveInstance(stream.getInstanceId());
        requireConfirm(confirm, stream.getName(),
                "删除四层转发「" + stream.getName() + "」将立即中断该端口转发，需二次确认");
        String confPath = stream.getConfPath() == null ? streamConfPath(inst, stream.getName()) : stream.getConfPath();
        String previous = readFile(confPath);
        applyDelete(inst, confPath, previous);
        streamMapper.deleteById(id);
        Long changeId = recordChange(inst.getId(), "DELETE_STREAM", "stream", id, confPath,
                previous == null ? "" : previous, "", previous == null ? "" : previous);
        return result("四层转发「" + stream.getName() + "」已删除", changeId);
    }

    public NginxActionResultVO toggleStream(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "状态仅支持 0/1");
        }
        OpsNginxStream stream = streamMapper.selectById(id);
        if (stream == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "转发不存在");
        }
        OpsNginxInstance inst = resolveInstance(stream.getInstanceId());
        String confPath = stream.getConfPath() == null ? streamConfPath(inst, stream.getName()) : stream.getConfPath();
        String previous = readFile(confPath);
        if (status == 1) {
            String content = renderStream(stream);
            ensureIncludes(inst);
            applyConfig(inst, confPath, content, previous);
        } else {
            applyDelete(inst, confPath, previous);
        }
        stream.setStatus(status);
        streamMapper.updateById(stream);
        Long changeId = recordChange(inst.getId(), "TOGGLE_STREAM", "stream", id, confPath,
                previous == null ? "" : previous,
                status == 1 ? readFile(confPath) : "", previous == null ? "" : previous);
        return result(status == 1 ? "转发「" + stream.getName() + "」已启用" : "转发「" + stream.getName() + "」已停用", changeId);
    }

    // ==================== 证书 ====================

    public PageResult<OpsNginxCert> certPage(Long instanceId, int pageNum, int pageSize) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        Page<OpsNginxCert> page = certMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsNginxCert>()
                        .eq(OpsNginxCert::getInstanceId, inst.getId())
                        .orderByDesc(OpsNginxCert::getCreatedAt));
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    /** 申请 Let's Encrypt 证书（HTTP-01 webroot；DNS-01 两步流在 S3 落地） */
    public NginxActionResultVO issueCert(NginxCertBody body) {
        if (body == null || body.getDomain() == null || body.getDomain().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少域名");
        }
        OpsNginxInstance inst = resolveInstance(body.getInstanceId());
        String domain = body.getDomain().trim();
        if (!DOMAIN.matcher(domain).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "域名非法");
        }
        String mode = body.getMode() == null ? "http01" : body.getMode();
        if (!"http01".equals(mode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "当前仅支持 http01（HTTP-01 webroot）申请；DNS-01 通配符申请将在后续版本开放");
        }
        String webroot = inst.getAcmeWebroot() == null ? "/www/wwwroot/psm-acme" : inst.getAcmeWebroot();
        String certDir = inst.getCertDir() == null ? defaultCertDir(inst) : inst.getCertDir();
        ensureDir(webroot);
        ensureDir(certDir);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("domain", domain);
        args.put("email", body.getEmail() == null ? "" : body.getEmail().trim());
        args.put("webroot", webroot);
        args.put("configDir", certDir);
        HostResult r = hostChannel.call("nginx.acmeIssue", args, "申请 Let's Encrypt 证书", 180);
        if (!r.isSuccess()) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "证书申请失败：" + r.errorText());
        }
        HostResult st = hostChannel.call("nginx.acmeStatus",
                Map.of("domain", domain, "configDir", certDir), "读取证书状态", 30);
        if (!st.isSuccess() || !st.dataBool("found")) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "证书申请成功但读取证书路径失败");
        }
        OpsNginxCert cert = new OpsNginxCert();
        cert.setInstanceId(inst.getId());
        cert.setDomain(domain);
        cert.setType("letsencrypt");
        cert.setCertPath(st.dataString("certPath"));
        cert.setKeyPath(st.dataString("keyPath"));
        cert.setIssuer("Let's Encrypt");
        cert.setNotAfter(parseNotAfter(st.dataString("notAfter")));
        cert.setAutoRenew(body.getAutoRenew() != null && body.getAutoRenew() == 1 ? 1 : 0);
        cert.setStatus("valid");
        cert.setCreatedAt(LocalDateTime.now());
        certMapper.insert(cert);
        Long changeId = recordChange(inst.getId(), "ISSUE_CERT", "cert", cert.getId(),
                cert.getCertPath(), "", cert.getCertPath(), "");
        return result("证书「" + domain + "」申请成功", changeId);
    }

    public NginxActionResultVO renewCert(Long id) {
        OpsNginxCert cert = certMapper.selectById(id);
        if (cert == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "证书不存在");
        }
        OpsNginxInstance inst = resolveInstance(cert.getInstanceId());
        String certDir = inst.getCertDir() == null ? defaultCertDir(inst) : inst.getCertDir();
        HostResult r = hostChannel.call("nginx.acmeRenew",
                Map.of("domain", cert.getDomain(), "configDir", certDir), "续期证书", 240);
        if (!r.isSuccess()) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "证书续期失败：" + r.errorText());
        }
        cert.setLastRenewAt(LocalDateTime.now());
        refreshCertStatus(inst, cert);
        certMapper.updateById(cert);
        Long changeId = recordChange(inst.getId(), "RENEW_CERT", "cert", cert.getId(),
                cert.getCertPath(), "", cert.getCertPath(), "");
        return result("证书「" + cert.getDomain() + "」续期成功", changeId);
    }

    /** 刷新证书到期状态（供页面与每日续期扫描使用） */
    public OpsNginxCert certStatus(Long id) {
        OpsNginxCert cert = certMapper.selectById(id);
        if (cert == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "证书不存在");
        }
        OpsNginxInstance inst = resolveInstance(cert.getInstanceId());
        refreshCertStatus(inst, cert);
        certMapper.updateById(cert);
        return cert;
    }

    /** 手动上传证书（PEM） */
    public NginxActionResultVO uploadCert(NginxCertBody body) {
        if (body == null || body.getDomain() == null || body.getDomain().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "缺少域名");
        }
        if (body.getCertContent() == null || body.getCertContent().isBlank()
                || body.getKeyContent() == null || body.getKeyContent().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "必须提供证书与私钥内容");
        }
        OpsNginxInstance inst = resolveInstance(body.getInstanceId());
        String domain = body.getDomain().trim();
        if (!DOMAIN.matcher(domain).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "域名非法");
        }
        String certDir = inst.getCertDir() == null ? defaultCertDir(inst) : inst.getCertDir();
        String dir = certDir + "/" + domain;
        ensureDir(dir);
        String certPath = dir + "/fullchain.pem";
        String keyPath = dir + "/privkey.pem";
        writeFile(certPath, body.getCertContent());
        writeFile(keyPath, body.getKeyContent());

        OpsNginxCert cert = new OpsNginxCert();
        cert.setInstanceId(inst.getId());
        cert.setDomain(domain);
        cert.setType("custom");
        cert.setCertPath(certPath);
        cert.setKeyPath(keyPath);
        cert.setIssuer("手动上传");
        cert.setNotAfter(parseCertEndDate(body.getCertContent()));
        cert.setAutoRenew(0);
        cert.setStatus("valid");
        cert.setCreatedAt(LocalDateTime.now());
        certMapper.insert(cert);
        Long changeId = recordChange(inst.getId(), "UPLOAD_CERT", "cert", cert.getId(),
                certPath, "", certPath, "");
        return result("证书「" + domain + "」已上传", changeId);
    }

    public NginxActionResultVO deleteCert(Long id, String confirm) {
        OpsNginxCert cert = certMapper.selectById(id);
        if (cert == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "证书不存在");
        }
        OpsNginxInstance inst = resolveInstance(cert.getInstanceId());
        long refs = siteMapper.selectCount(new LambdaQueryWrapper<OpsNginxSite>()
                .eq(OpsNginxSite::getInstanceId, inst.getId())
                .eq(OpsNginxSite::getCertId, id));
        if (refs > 0) {
            throw new ServiceException(ErrorCode.NGINX_CERT_IN_USE);
        }
        requireConfirm(confirm, cert.getDomain(),
                "删除证书「" + cert.getDomain() + "」需二次确认");
        certMapper.deleteById(id);
        Long changeId = recordChange(inst.getId(), "DELETE_CERT", "cert", id,
                cert.getCertPath(), cert.getCertPath(), "", "");
        return result("证书「" + cert.getDomain() + "」已删除", changeId);
    }

    private void refreshCertStatus(OpsNginxInstance inst, OpsNginxCert cert) {
        if (!"letsencrypt".equals(cert.getType())) {
            return;
        }
        String certDir = inst.getCertDir() == null ? defaultCertDir(inst) : inst.getCertDir();
        try {
            HostResult st = hostChannel.call("nginx.acmeStatus",
                    Map.of("domain", cert.getDomain(), "configDir", certDir), "读取证书状态", 30);
            if (st.isSuccess() && st.dataBool("found")) {
                cert.setCertPath(st.dataString("certPath"));
                cert.setKeyPath(st.dataString("keyPath"));
                cert.setNotAfter(parseNotAfter(st.dataString("notAfter")));
            }
        } catch (RuntimeException e) {
            log.warn("刷新证书状态失败: {} - {}", cert.getDomain(), e.getMessage());
        }
        cert.setStatus(certStatusOf(cert.getNotAfter()));
    }

    private String certStatusOf(LocalDateTime notAfter) {
        if (notAfter == null) {
            return "pending";
        }
        LocalDateTime now = LocalDateTime.now();
        if (notAfter.isBefore(now)) {
            return "expired";
        }
        if (notAfter.isBefore(now.plusDays(30))) {
            return "expiring";
        }
        return "valid";
    }

    // ==================== 变更历史与回滚 ====================

    public PageResult<OpsNginxChange> changePage(Long instanceId, int pageNum, int pageSize) {
        Page<OpsNginxChange> page = changeMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsNginxChange>()
                        .eq(instanceId != null, OpsNginxChange::getInstanceId, instanceId)
                        .orderByDesc(OpsNginxChange::getCreatedAt));
        for (OpsNginxChange row : page.getRecords()) {
            row.setBeforeConf(null);
            row.setAfterConf(null);
            row.setRollbackConf(null);
        }
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    public OpsNginxChange changeDetail(Long id) {
        OpsNginxChange row = changeMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "变更记录不存在");
        }
        return row;
    }

    /** 回滚：把受影响配置文件恢复到变更前内容（回滚后同样 -t + reload） */
    public NginxActionResultVO rollback(Long id, String confirm) {
        OpsNginxChange row = changeMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "变更记录不存在");
        }
        if (row.getRolledBack() != null && row.getRolledBack() == 1) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "该变更已回滚过");
        }
        if (row.getConfPath() == null || row.getConfPath().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "该变更无配置文件快照，不可回滚");
        }
        requireConfirm(confirm, "ROLLBACK", "回滚 nginx 变更属于高危操作，需二次确认");
        OpsNginxInstance inst = resolveInstance(row.getInstanceId());
        String target = row.getRollbackConf() == null ? "" : row.getRollbackConf();
        String current = readFile(row.getConfPath());
        if (target.isBlank()) {
            applyDelete(inst, row.getConfPath(), current);
        } else {
            applyConfig(inst, row.getConfPath(), target, current);
        }
        OpsNginxChange mark = new OpsNginxChange();
        mark.setId(id);
        mark.setRolledBack(1);
        changeMapper.updateById(mark);
        Long changeId = recordChange(inst.getId(), "ROLLBACK", "change", id, row.getConfPath(),
                current == null ? "" : current, target, current == null ? "" : current);
        return result("已回滚变更 #" + id, changeId);
    }

    // ==================== 运维动作 ====================

    public NginxActionResultVO reload() {
        OpsNginxInstance inst = resolveInstance(null);
        HostResult t = hostChannel.call("nginx.test",
                testArgs(inst), "校验 nginx 配置", 60);
        if (!t.isSuccess()) {
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "配置校验失败，未执行重载：" + t.errorText());
        }
        HostResult r = hostChannel.call("nginx.reload", Map.of(), "重载 nginx", 60);
        if (!r.isSuccess()) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "重载失败：" + r.errorText());
        }
        Long changeId = recordChange(inst.getId(), "RELOAD", "instance", inst.getId(),
                inst.getConfPath(), "", "", "");
        return result("nginx 已平滑重载（SIGHUP）", changeId);
    }

    public NginxActionResultVO test() {
        OpsNginxInstance inst = resolveInstance(null);
        HostResult t = hostChannel.call("nginx.test",
                testArgs(inst), "校验 nginx 配置", 60);
        if (!t.isSuccess()) {
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "配置校验失败：" + t.errorText());
        }
        NginxActionResultVO vo = new NginxActionResultVO();
        vo.setCommand("nginx -t -c " + inst.getConfPath());
        vo.setMessage("配置语法校验通过");
        vo.setRollbackable(false);
        return vo;
    }

    // ==================== 现有站点（只读） ====================

    /** 解析 nginx -T 全文，列出宝塔既有 vhost 站点（只读，不含面板自建） */
    public List<Map<String, Object>> existing(Long instanceId) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        HostResult r = hostChannel.call("host.exec",
                Map.of("argv", List.of("nginx", "-T")), "读取 nginx 全量配置", 60);
        String dump = r.text();
        String managedDir = inst.getManagedDir();
        List<Map<String, Object>> out = new ArrayList<>();
        // nginx -T 输出形如 "# configuration file /path:" 后接该文件内容
        for (String section : dump.split("# configuration file ")) {
            int nl = section.indexOf('\n');
            if (nl < 0) {
                continue;
            }
            String file = section.substring(0, nl).trim();
            if (file.isEmpty()) {
                continue;
            }
            if (managedDir != null && file.startsWith(managedDir)) {
                continue; // 面板自建，跳过
            }
            String body = section.substring(nl + 1);
            List<String> serverNames = matchList(body, "server_name\\s+([^;]+);");
            List<String> listens = matchList(body, "listen\\s+([^;]+);");
            if (serverNames.isEmpty() && listens.isEmpty()) {
                continue; // 非 server 块（如 upstream / 全局指令）
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("confFile", file);
            item.put("serverNames", serverNames);
            item.put("listens", listens);
            item.put("ssl", body.contains("ssl_certificate"));
            item.put("root", firstMatch(body, "root\\s+([^;]+);"));
            item.put("proxyPass", firstMatch(body, "proxy_pass\\s+([^;]+);"));
            out.add(item);
        }
        return out;
    }

    // ==================== 日志 ====================

    /** 列出实例日志目录下的 .log 文件 */
    public List<Map<String, Object>> logList(Long instanceId) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        String logDir = inst.getLogDir() == null ? "/www/wwwlogs" : inst.getLogDir();
        Path dir = Path.of(logDir);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .forEach(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.getFileName().toString());
                        try {
                            m.put("size", Files.size(p));
                        } catch (IOException e) {
                            m.put("size", -1L);
                        }
                        out.add(m);
                    });
        } catch (IOException e) {
            log.warn("列出日志失败: {}", e.getMessage());
        }
        return out;
    }

    /** 尾部读取指定日志文件（文件名限实例 log_dir 下，防路径穿越） */
    public List<String> logTail(Long instanceId, String fileName, int lines) {
        OpsNginxInstance inst = resolveInstance(instanceId);
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "日志文件名非法");
        }
        String logDir = inst.getLogDir() == null ? "/www/wwwlogs" : inst.getLogDir();
        Path file = Path.of(logDir, fileName).normalize();
        if (!file.startsWith(Path.of(logDir).normalize()) || !Files.isRegularFile(file)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "日志文件不存在");
        }
        return tail(file, Math.max(1, Math.min(lines, 500)));
    }

    // ==================== include 自愈与写盘 ====================

    /** 确保主配置 include 了托管目录（幂等；改动则备份 + -t + reload） */
    private void ensureIncludes(OpsNginxInstance inst) {
        if (inst.getConfPath() == null || inst.getManagedDir() == null) {
            return;
        }
        String conf = readFile(inst.getConfPath());
        if (conf == null) {
            throw new ServiceException(ErrorCode.NGINX_UNAVAILABLE.getCode(),
                    "无法读取主配置 " + inst.getConfPath());
        }
        String httpInclude = "include " + inst.getManagedDir() + "/*.conf;";
        String streamInclude = inst.getStreamDir() == null
                ? null : "include " + inst.getStreamDir() + "/*.conf;";
        boolean httpMissing = !conf.contains(httpInclude);
        boolean streamMissing = streamInclude != null && !conf.contains(streamInclude);
        if (!httpMissing && !streamMissing) {
            return;
        }
        String next = conf;
        if (httpMissing) {
            next = injectAfter(next,
                    "include /www/server/panel/vhost/nginx/*.conf;", httpInclude);
            if (next == null) {
                next = injectAfterHttpBlock(conf, httpInclude);
            }
        }
        if (streamMissing) {
            next = injectAfter(next,
                    "include /www/server/panel/vhost/nginx/tcp/*.conf;", streamInclude);
            if (next == null) {
                next = injectAfterStreamBlock(next == null ? conf : next, streamInclude);
            }
        }
        if (next == null || next.equals(conf)) {
            log.warn("未能自动注入 include，请手动在 {} 中添加 {} / {}", inst.getConfPath(),
                    httpInclude, streamInclude);
            return;
        }
        applyConfig(inst, inst.getConfPath(), next, conf);
        log.info("已注入 include：{} / {}", httpInclude, streamInclude);
    }

    /** 在指定锚点行后插入 include（未找到返回 null） */
    private String injectAfter(String conf, String anchor, String includeLine) {
        if (conf == null || !conf.contains(anchor)) {
            return null;
        }
        return conf.replace(anchor, anchor + "\n" + includeLine);
    }

    private String injectAfterHttpBlock(String conf, String includeLine) {
        return injectAfterBlockOpen(conf, "http", includeLine);
    }

    private String injectAfterStreamBlock(String conf, String includeLine) {
        return injectAfterBlockOpen(conf, "stream", includeLine);
    }

    /** 在 http/stream 块开启处注入（兜底；支持 `http {` 与 `http\n{` 两种写法） */
    private String injectAfterBlockOpen(String conf, String block, String includeLine) {
        Pattern p = Pattern.compile("(?m)^(\\s*" + block + "\\s*\\{?)\\s*$");
        Matcher m = p.matcher(conf);
        if (!m.find()) {
            return null;
        }
        int pos = m.end();
        return conf.substring(0, pos) + "\n" + includeLine + conf.substring(pos);
    }

    /** 写新文件并生效；失败恢复 previousContent（null 表示新文件，失败即删） */
    private void applyConfig(OpsNginxInstance inst, String targetPath, String content, String previous) {
        writeFile(targetPath, content);
        HostResult test = hostChannel.call("nginx.test",
                testArgs(inst), "校验 nginx 配置", 60);
        if (!test.isSuccess()) {
            rollbackFile(targetPath, previous);
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "配置校验失败，已回滚：" + test.errorText());
        }
        HostResult reload = hostChannel.call("nginx.reload", Map.of(), "重载 nginx", 60);
        if (!reload.isSuccess()) {
            rollbackFile(targetPath, previous);
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "重载失败，已回滚：" + reload.errorText());
        }
    }

    /** 删除文件并生效；失败恢复 previousContent */
    private void applyDelete(OpsNginxInstance inst, String targetPath, String previous) {
        try {
            Files.deleteIfExists(Path.of(targetPath));
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "删除配置文件失败: " + e.getMessage());
        }
        HostResult test = hostChannel.call("nginx.test",
                testArgs(inst), "校验 nginx 配置", 60);
        if (!test.isSuccess()) {
            if (previous != null && !previous.isEmpty()) {
                writeFile(targetPath, previous);
            }
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "删除后校验失败，已回滚：" + test.errorText());
        }
        HostResult reload = hostChannel.call("nginx.reload", Map.of(), "重载 nginx", 60);
        if (!reload.isSuccess()) {
            if (previous != null && !previous.isEmpty()) {
                writeFile(targetPath, previous);
            }
            throw new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
                    "重载失败，已回滚：" + reload.errorText());
        }
    }

    private void rollbackFile(String targetPath, String previous) {
        try {
            Path file = Path.of(targetPath);
            if (previous == null || previous.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, previous, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("回滚配置文件失败: {} - {}", targetPath, e.getMessage());
        }
    }

    private void writeFile(String targetPath, String content) {
        try {
            Path file = Path.of(targetPath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "写入文件失败: " + e.getMessage());
        }
    }

    private void ensureDir(String dir) {
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "创建目录失败: " + e.getMessage());
        }
    }

    private String readFile(String path) {
        if (path == null) {
            return null;
        }
        try {
            Path p = Path.of(path);
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 组装 nginx.test 的调用参数（confPath 为空时不带 -c，避免 Map.of 空指针） */
    private Map<String, Object> testArgs(OpsNginxInstance inst) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (inst.getConfPath() != null && !inst.getConfPath().isBlank()) {
            args.put("confPath", inst.getConfPath());
        }
        return args;
    }

    // ==================== 渲染 ====================

    private String renderSite(OpsNginxSite site, OpsNginxInstance inst) {
        boolean ssl = site.getSslMode() != null && !"off".equals(site.getSslMode());
        String certPath = "";
        String keyPath = "";
        if (ssl && site.getCertId() != null) {
            OpsNginxCert cert = certMapper.selectById(site.getCertId());
            if (cert != null) {
                certPath = cert.getCertPath() == null ? "" : cert.getCertPath();
                keyPath = cert.getKeyPath() == null ? "" : cert.getKeyPath();
            }
        }
        String upstream = "";
        if ("proxy".equals(site.getSiteType())) {
            if (site.getUpstreamId() != null) {
                OpsNginxUpstream up = upstreamMapper.selectById(site.getUpstreamId());
                upstream = up == null ? "" : "http://" + up.getName();
            } else if (site.getUpstreamInline() != null) {
                upstream = site.getUpstreamInline();
            }
        }
        List<String> domains = parseDomains(site.getDomains());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("siteName", site.getName());
        model.put("serverNames", String.join(" ", domains));
        model.put("primaryDomain", domains.isEmpty() ? site.getName() : domains.get(0));
        model.put("sslEnabled", ssl);
        model.put("certPath", certPath);
        model.put("keyPath", keyPath);
        model.put("httpRedirect", site.getHttpRedirect() != null && site.getHttpRedirect() == 1);
        model.put("hsts", site.getHsts() != null && site.getHsts() == 1);
        model.put("upstream", upstream);
        model.put("staticRoot", site.getStaticRoot() == null ? "" : site.getStaticRoot());
        model.put("locationsBlock", renderLocations(parseLocations(site.getLocationsJson())));
        model.put("logDir", inst.getLogDir() == null ? "/www/wwwlogs" : inst.getLogDir());
        model.put("name", site.getName());
        String template = "proxy".equals(site.getSiteType())
                ? "nginx-site-proxy.ftl" : "nginx-site-static.ftl";
        return render(template, model);
    }

    private String renderUpstream(OpsNginxUpstream up) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", up.getName());
        String directive = switch (up.getStrategy() == null ? "round_robin" : up.getStrategy()) {
            case "least_conn" -> "least_conn;";
            case "ip_hash" -> "ip_hash;";
            default -> "";
        };
        model.put("strategyDirective", directive);
        model.put("servers", buildServerLines(parseServers(up.getServersJson())));
        model.put("keepalive", up.getKeepalive() == null ? 0 : up.getKeepalive());
        return render("nginx-upstream.ftl", model);
    }

    private String renderStream(OpsNginxStream stream) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("port", stream.getListenPort());
        model.put("listenSuffix", "udp".equals(stream.getProtocol()) ? " udp" : "");
        model.put("upstreamHost", stream.getUpstreamHost());
        model.put("upstreamPort", stream.getUpstreamPort());
        model.put("proxyTimeout", stream.getProxyTimeout() == null ? 600 : stream.getProxyTimeout());
        return render("nginx-stream.ftl", model);
    }

    private String render(String template, Map<String, Object> model) {
        try (StringWriter writer = new StringWriter()) {
            FTL.getTemplate(template).process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("渲染模板失败: {} - {}", template, e.getMessage());
            throw new ServiceException(ErrorCode.ERROR.getCode(), "配置模板渲染失败：" + e.getMessage());
        }
    }

    /** 把结构化 location 列表渲染成若干 location 块 */
    private String renderLocations(List<NginxSiteBody.NginxLocation> locations) {
        StringBuilder sb = new StringBuilder();
        for (NginxSiteBody.NginxLocation loc : locations) {
            sb.append("    location ").append(loc.getPath()).append(" {\n");
            switch (loc.getType()) {
                case "proxy" -> {
                    sb.append("        proxy_pass ").append(loc.getUpstream()).append(";\n");
                    sb.append("        proxy_http_version 1.1;\n");
                    sb.append("        proxy_set_header Host $host;\n");
                    sb.append("        proxy_set_header X-Real-IP $remote_addr;\n");
                    sb.append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
                    sb.append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
                }
                case "static" -> {
                    sb.append("        root ").append(loc.getStaticRoot()).append(";\n");
                    sb.append("        try_files $uri $uri/ =404;\n");
                }
                case "redirect" -> sb.append("        return 301 ").append(loc.getRedirectTarget()).append(";\n");
                case "deny" -> sb.append("        deny all;\n");
                default -> {
                    // 已在 validateLocations 拦截
                }
            }
            sb.append("    }\n");
        }
        return sb.toString();
    }

    private List<String> buildServerLines(List<NginxUpstreamBody.Server> servers) {
        List<String> out = new ArrayList<>();
        for (NginxUpstreamBody.Server s : servers) {
            StringBuilder sb = new StringBuilder();
            sb.append(s.getHost()).append(':').append(s.getPort());
            if (s.getWeight() != null) {
                sb.append(" weight=").append(s.getWeight());
            }
            if (s.getMaxFails() != null) {
                sb.append(" max_fails=").append(s.getMaxFails());
            }
            if (s.getBackup() != null && s.getBackup()) {
                sb.append(" backup");
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ==================== 校验辅助 ====================

    private void checkDomainConflict(OpsNginxInstance inst, Long excludeId, List<String> domains) {
        for (OpsNginxSite s : siteMapper.selectList(new LambdaQueryWrapper<OpsNginxSite>()
                .eq(OpsNginxSite::getInstanceId, inst.getId()))) {
            if (excludeId != null && excludeId.equals(s.getId())) {
                continue;
            }
            for (String d : parseDomains(s.getDomains())) {
                if (domains.contains(d)) {
                    throw new ServiceException(ErrorCode.NGINX_DOMAIN_CONFLICT.getCode(),
                            "域名 " + d + " 已被站点「" + s.getName() + "」使用");
                }
            }
        }
    }

    private void requireConfirm(String confirm, String keyword, String reason) {
        if (confirm != null && confirm.trim().equalsIgnoreCase(keyword)) {
            return;
        }
        throw new ServiceException(ErrorCode.NGINX_GUARD_TRIGGERED.getCode(), reason);
    }

    private List<String> parseDomains(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    // ==================== 序列化 / 工具 ====================

    private String serialize(List<?> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "数据序列化失败");
        }
    }

    private List<NginxSiteBody.NginxLocation> parseLocations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            NginxSiteBody.NginxLocation[] arr =
                    objectMapper.readValue(json, NginxSiteBody.NginxLocation[].class);
            return arr == null ? List.of() : Arrays.asList(arr);
        } catch (Exception e) {
            log.warn("解析 locations 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<NginxUpstreamBody.Server> parseServers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            NginxUpstreamBody.Server[] arr =
                    objectMapper.readValue(json, NginxUpstreamBody.Server[].class);
            return arr == null ? List.of() : Arrays.asList(arr);
        } catch (Exception e) {
            log.warn("解析 servers 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String defaultCertDir(OpsNginxInstance inst) {
        if (inst.getManagedDir() != null) {
            return inst.getManagedDir() + "/certs";
        }
        return "/www/server/nginx/conf/serverpanel.d/certs";
    }

    private String siteConfPath(OpsNginxInstance inst, String name) {
        return inst.getManagedDir() + "/" + name + ".conf";
    }

    private String upstreamConfPath(OpsNginxInstance inst, String name) {
        return inst.getManagedDir() + "/" + name + ".upstream.conf";
    }

    private String streamConfPath(OpsNginxInstance inst, String name) {
        return inst.getStreamDir() + "/" + name + ".conf";
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private LocalDateTime parseNotAfter(String notAfter) {
        if (notAfter == null || notAfter.isBlank()) {
            return null;
        }
        // openssl x509 -enddate 输出形如 "Jan  1 12:00:00 2030 GMT"（个位日补空格）
        try {
            String normalized = notAfter.trim().replaceAll("\\s+", " ");
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(
                    normalized, java.time.format.DateTimeFormatter.ofPattern(
                            "MMM d HH:mm:ss yyyy z", java.util.Locale.US));
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 PEM 内容里解析 notAfter（用于手动上传证书；纯 Java 解析，不依赖 openssl） */
    private LocalDateTime parseCertEndDate(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert =
                    (java.security.cert.X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(
                                    pem.getBytes(StandardCharsets.UTF_8)));
            return cert.getNotAfter().toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            log.warn("解析手动证书有效期失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> matchList(String body, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(body);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    private String firstMatch(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }

    private List<String> tail(Path file, int lines) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            if (len == 0) {
                return List.of();
            }
            long readLen = Math.min(len, 256L * 1024L);
            raf.seek(len - readLen);
            byte[] buf = new byte[(int) readLen];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            if (readLen < len) {
                int nl = text.indexOf('\n');
                if (nl >= 0) {
                    text = text.substring(nl + 1);
                }
            }
            String[] all = text.split("\\r?\\n", -1);
            int from = Math.max(0, all.length - lines);
            List<String> out = new ArrayList<>();
            for (int i = from; i < all.length; i++) {
                out.add(all[i]);
            }
            return out;
        } catch (IOException e) {
            log.warn("读取日志尾部失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Long recordChange(Long instanceId, String op, String targetType, Long targetId,
                              String confPath, String before, String after, String rollback) {
        try {
            OpsNginxChange row = new OpsNginxChange();
            row.setInstanceId(instanceId);
            row.setOp(op);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setConfPath(confPath);
            row.setBeforeConf(before == null ? null : cut(before, 100000));
            row.setAfterConf(after == null ? null : cut(after, 100000));
            row.setRollbackConf(rollback == null ? null : cut(rollback, 100000));
            row.setRolledBack(0);
            row.setResult(0);
            row.setOperator(currentOperator());
            row.setCreatedAt(LocalDateTime.now());
            changeMapper.insert(row);
            return row.getId();
        } catch (RuntimeException e) {
            log.warn("记录 nginx 变更快照失败: {}", e.getMessage());
            return null;
        }
    }

    private NginxActionResultVO result(String message, Long changeId) {
        NginxActionResultVO vo = new NginxActionResultVO();
        vo.setCommand("nginx -t && nginx -s reload");
        vo.setMessage(message);
        vo.setChangeId(changeId);
        vo.setRollbackable(changeId != null);
        return vo;
    }

    private String currentOperator() {
        try {
            return LoginHelper.isLogin() ? LoginHelper.getUsername() : "system";
        } catch (RuntimeException e) {
            return "system";
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
