package com.serverpanel.appstack.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.config.NginxProperties;
import com.serverpanel.appstack.dto.WebsiteBody;
import com.serverpanel.appstack.entity.AppWebsite;
import com.serverpanel.appstack.mapper.AppWebsiteMapper;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.file.security.PathGuard;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Nginx 网站管理。
 *
 * <p>工作流：FreeMarker 渲染站点配置 → 写入 {@code conf-dir/{domain}.conf}
 * → {@code nginx -t} 校验 → {@code nginx -s reload} 生效。
 * 校验/重载失败时自动回滚旧配置（或删除新文件），保证面板数据与
 * 磁盘配置始终一致。
 *
 * <p>安全约束：
 * <ul>
 *   <li>域名必须匹配严格正则，作为文件名直接使用（杜绝路径穿越）；</li>
 *   <li>static_root 必须位于文件模块根目录白名单内（复用 PathGuard）；</li>
 *   <li>所有系统命令经 CommandExecutor argv 白名单执行。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebsiteService {

    private static final Configuration FTL = new Configuration(Configuration.VERSION_2_3_33);
    private static final Pattern DOMAIN =
        Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$");

    static {
        FTL.setTemplateLoader(
            new ClassTemplateLoader(WebsiteService.class.getClassLoader(), "templates"));
        FTL.setDefaultEncoding(StandardCharsets.UTF_8.name());
    }

    private final AppWebsiteMapper websiteMapper;
    private final CommandExecutor commandExecutor;
    private final NginxProperties nginxProperties;
    private final PathGuard pathGuard;

    // ==================== 查询 ====================

    public PageResult<AppWebsite> page(PageQuery query, String keyword) {
        Page<AppWebsite> page = websiteMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<AppWebsite>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(AppWebsite::getDomain, keyword)
                    .or().like(AppWebsite::getSiteName, keyword))
                .orderByDesc(AppWebsite::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** nginx 是否可用（二进制可执行） */
    public boolean nginxAvailable() {
        try {
            return commandExecutor.exec(nginxProperties.getBinary(), "-v").getExitCode() == 0;
        } catch (ServiceException e) {
            return false;
        }
    }

    // ==================== 写操作 ====================

    public void create(WebsiteBody body) {
        if (websiteMapper.selectCount(new LambdaQueryWrapper<AppWebsite>()
                .eq(AppWebsite::getDomain, body.getDomain())) > 0) {
            throw new ServiceException(ErrorCode.WEBSITE_DOMAIN_EXISTS);
        }
        validateBody(body);

        AppWebsite site = new AppWebsite();
        site.setDomain(body.getDomain());
        site.setSiteName(sanitizeName(body.getSiteName()));
        site.setSiteType(body.getSiteType());
        site.setUpstream(body.getUpstream());
        site.setStaticRoot(body.getStaticRoot());
        site.setSslEnabled(body.getSslEnabled() != null ? body.getSslEnabled() : 0);
        site.setCertPath(body.getCertPath());
        site.setKeyPath(body.getKeyPath());
        site.setConfPath(confFile(body.getDomain()).toString());
        site.setStatus(1);
        site.setRemark(body.getRemark());

        writeConfigWithRollback(site, null);
        try {
            websiteMapper.insert(site);
        } catch (Exception e) {
            removeConfigSilently(site.getDomain());
            throw e;
        }
    }

    public void update(WebsiteBody body) {
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST);
        }
        AppWebsite db = websiteMapper.selectById(body.getId());
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        validateBody(body);
        if (!db.getDomain().equals(body.getDomain())) {
            if (websiteMapper.selectCount(new LambdaQueryWrapper<AppWebsite>()
                    .eq(AppWebsite::getDomain, body.getDomain())) > 0) {
                throw new ServiceException(ErrorCode.WEBSITE_DOMAIN_EXISTS);
            }
        }

        String oldDomain = db.getDomain();
        String oldContent = readSilently(confFile(oldDomain));

        db.setDomain(body.getDomain());
        db.setSiteName(sanitizeName(body.getSiteName()));
        db.setSiteType(body.getSiteType());
        db.setUpstream(body.getUpstream());
        db.setStaticRoot(body.getStaticRoot());
        db.setSslEnabled(body.getSslEnabled() != null ? body.getSslEnabled() : 0);
        db.setCertPath(body.getCertPath());
        db.setKeyPath(body.getKeyPath());
        db.setRemark(body.getRemark());
        db.setConfPath(confFile(body.getDomain()).toString());

        // 域名变更时旧配置会残留为孤儿文件，先移除并生效
        if (!oldDomain.equals(body.getDomain())) {
            removeConfigWithRollback(oldDomain);
        }
        writeConfigWithRollback(db, oldContent);
        websiteMapper.updateById(db);
    }

    public void toggle(Long id, Integer status) {
        AppWebsite db = websiteMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "状态仅支持 0/1");
        }
        String oldContent = readSilently(confFile(db.getDomain()));
        if (status == 1) {
            writeConfigWithRollback(db, oldContent);
        } else {
            removeConfigWithRollback(db.getDomain());
        }
        db.setStatus(status);
        websiteMapper.updateById(db);
    }

    public void delete(Long id) {
        AppWebsite db = websiteMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        removeConfigWithRollback(db.getDomain());
        websiteMapper.deleteById(id);
    }

    /** 站点配置文件原始内容（在线编辑查看用） */
    public String confContent(Long id) {
        AppWebsite db = websiteMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        String content = readSilently(confFile(db.getDomain()));
        return content == null ? "" : content;
    }

    // ==================== 配置生成与回滚 ====================

    /**
     * 写入并生效站点配置；失败时恢复 previousContent（null 表示新文件，失败即删除）。
     */
    private void writeConfigWithRollback(AppWebsite site, String previousContent) {
        Path file = confFile(site.getDomain());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(site), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("write nginx conf failed: {}", file, e);
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "写入 nginx 配置失败: " + e.getMessage());
        }
        ExecResult test = commandExecutor.exec(nginxProperties.getBinary(), "-t");
        if (test.getExitCode() != 0) {
            rollbackFile(file, previousContent);
            throw nginxError("nginx 配置校验失败", test);
        }
        ExecResult reload = commandExecutor.exec(nginxProperties.getBinary(), "-s", "reload");
        if (reload.getExitCode() != 0) {
            rollbackFile(file, previousContent);
            throw nginxError("nginx 重载失败", reload);
        }
    }

    /**
     * 移除站点配置并生效；失败时恢复原文件内容。
     */
    private void removeConfigWithRollback(String domain) {
        Path file = confFile(domain);
        if (!Files.exists(file)) {
            return;
        }
        String oldContent = readSilently(file);
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "删除 nginx 配置失败: " + e.getMessage());
        }
        ExecResult test = commandExecutor.exec(nginxProperties.getBinary(), "-t");
        if (test.getExitCode() != 0) {
            restoreFile(file, oldContent);
            throw nginxError("删除配置后校验失败，已回滚", test);
        }
        ExecResult reload = commandExecutor.exec(nginxProperties.getBinary(), "-s", "reload");
        if (reload.getExitCode() != 0) {
            restoreFile(file, oldContent);
            throw nginxError("nginx 重载失败，已回滚", reload);
        }
    }

    private void rollbackFile(Path file, String previousContent) {
        if (previousContent == null) {
            removeConfigSilently(file);
        } else {
            restoreFile(file, previousContent);
        }
    }

    private void restoreFile(Path file, String content) {
        try {
            if (content == null) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("rollback nginx conf failed: {}", file, e);
        }
    }

    private void removeConfigSilently(String domain) {
        removeConfigSilently(confFile(domain));
    }

    private void removeConfigSilently(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("delete nginx conf failed: {}", file, e);
        }
    }

    private String readSilently(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private ServiceException nginxError(String prefix, ExecResult result) {
        String msg = result.getStderr().isBlank() ? result.getStdout() : result.getStderr();
        return new ServiceException(ErrorCode.NGINX_CONF_INVALID.getCode(),
            prefix + (msg.isBlank() ? "" : ": " + msg.trim()));
    }

    // ==================== 渲染与校验 ====================

    private String render(AppWebsite site) {
        String template = "proxy".equals(site.getSiteType())
            ? "website-proxy.ftl" : "website-static.ftl";
        boolean ssl = site.getSslEnabled() != null && site.getSslEnabled() == 1;
        Map<String, Object> model = Map.of(
            "siteName", site.getSiteName(),
            "domain", site.getDomain(),
            "sslEnabled", ssl,
            "upstream", site.getUpstream() == null ? "" : site.getUpstream(),
            "staticRoot", site.getStaticRoot() == null ? "" : site.getStaticRoot(),
            "certPath", site.getCertPath() == null ? "" : site.getCertPath(),
            "keyPath", site.getKeyPath() == null ? "" : site.getKeyPath());
        try (StringWriter writer = new StringWriter()) {
            FTL.getTemplate(template).process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("render nginx template failed: {}", template, e);
            throw new ServiceException(ErrorCode.ERROR.getCode(), "配置模板渲染失败");
        }
    }

    /** 站点级参数校验（Controller 的 Bean Validation 之外的第二道防线） */
    private void validateBody(WebsiteBody body) {
        if (body.getDomain() == null || !DOMAIN.matcher(body.getDomain()).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "域名格式非法");
        }
        if ("static".equals(body.getSiteType())) {
            if (body.getStaticRoot() == null || body.getStaticRoot().isBlank()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "静态站点必须指定站点根目录");
            }
            try {
                pathGuard.resolveExisting(body.getStaticRoot());
            } catch (ServiceException e) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "站点根目录不合法: " + e.getMessage());
            }
        } else {
            if (body.getUpstream() == null || body.getUpstream().isBlank()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "反代站点必须指定 upstream");
            }
        }
        if (body.getSslEnabled() != null && body.getSslEnabled() == 1) {
            if (body.getCertPath() == null || body.getCertPath().isBlank()
                || body.getKeyPath() == null || body.getKeyPath().isBlank()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, "开启 HTTPS 必须提供证书与私钥路径");
            }
        }
    }

    /** 站点名称进入模板注释前去除换行，防止注入新指令 */
    private String sanitizeName(String name) {
        return name == null ? "" : name.replaceAll("[\\r\\n]+", " ").trim();
    }

    private Path confFile(String domain) {
        return nginxProperties.confDirPath().resolve(domain + ".conf");
    }
}
