package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.system.dto.dashboard.QuickService;
import com.serverpanel.system.dto.dashboard.SshLoginInfo;
import com.serverpanel.system.dto.dashboard.VisitSource;
import com.serverpanel.system.entity.SysLoginLog;
import com.serverpanel.system.entity.SysQuickNav;
import com.serverpanel.system.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作台（dashboard/workspace）聚合数据服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** journalctl -o short-iso 时间 + sshd 日志行 */
    private static final Pattern SSH_ACCEPTED = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:?\\d{2})\\s+\\S+\\s+sshd\\[\\d+]:"
            + " Accepted\\s+(\\w+)\\s+for\\s+(\\S+)\\s+from\\s+(\\S+)\\s+port\\s+(\\d+)");

    private static final DateTimeFormatter DISPLAY_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 展示名 -> 匹配关键字列表（用于从运行中 systemd 单元识别运行状态） */
    private static final Map<String, List<String>> SERVICE_KEYWORDS = buildServiceKeywords();

    private final CommandExecutor commandExecutor;
    private final SysLoginLogMapper loginLogMapper;
    private final IpRegionService ipRegionService;
    private final QuickNavService quickNavService;

    /**
     * 最近 N 次 SSH 登录成功记录（来自 journald sshd 日志，最新在前）。
     */
    public List<SshLoginInfo> sshLogins(int limit) {
        List<SshLoginInfo> result = new ArrayList<>();
        try {
            ExecResult exec = commandExecutor.exec(
                "journalctl", "_COMM=sshd", "-o", "short-iso", "--no-pager", "-n", "2000");
            if (exec.getExitCode() != 0) {
                return result;
            }
            for (String line : exec.getStdout().split("\\r?\\n")) {
                Matcher m = SSH_ACCEPTED.matcher(line);
                if (!m.find()) {
                    continue;
                }
                SshLoginInfo info = new SshLoginInfo();
                info.setTime(formatTime(m.group(1)));
                info.setMethod(m.group(2));
                info.setUsername(m.group(3));
                info.setIp(m.group(4));
                info.setPort(m.group(5));
                result.add(info);
                if (result.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query ssh login logs: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 快捷导航：读取 sys_quick_nav 启用配置（排序升序），并实时识别运行状态。
     */
    public List<QuickService> quickServices() {
        List<SysQuickNav> navs = quickNavService.listEnabled();
        if (navs.isEmpty()) {
            return List.of();
        }
        List<String> runningUnits = runningUnitNames();
        List<QuickService> result = new ArrayList<>();
        for (SysQuickNav nav : navs) {
            List<String> keywords = SERVICE_KEYWORDS.getOrDefault(
                nav.getDisplayName(), List.of());
            QuickService svc = new QuickService();
            svc.setName(keywords.isEmpty()
                ? nav.getDisplayName().toLowerCase(Locale.ROOT)
                : keywords.get(0));
            svc.setDisplayName(nav.getDisplayName());
            svc.setPort(nav.getPort() == null ? -1 : nav.getPort());
            svc.setPath(nav.getPath() == null ? "" : nav.getPath());
            svc.setIcon(nav.getIcon() == null || nav.getIcon().isBlank()
                ? "lucide:app-window" : nav.getIcon());
            svc.setRunning(isRunning(runningUnits, keywords));
            result.add(svc);
        }
        return result;
    }

    /**
     * 访问来源：近 7 天登录成功的 IP 按地区聚合（次数降序）。
     */
    public List<VisitSource> visitSources() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<SysLoginLog> logs = loginLogMapper.selectList(
            new LambdaQueryWrapper<SysLoginLog>()
                .eq(SysLoginLog::getStatus, 1)
                .ge(SysLoginLog::getCreatedAt, since)
                .orderByDesc(SysLoginLog::getCreatedAt)
                .last("LIMIT 500"));
        Map<String, Long> counter = new LinkedHashMap<>();
        for (SysLoginLog log : logs) {
            String region = ipRegionService.region(log.getIp());
            counter.merge(region, 1L, Long::sum);
        }
        List<VisitSource> result = new ArrayList<>();
        counter.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> {
                VisitSource source = new VisitSource();
                source.setRegion(e.getKey());
                source.setCount(e.getValue());
                result.add(source);
            });
        return result;
    }

    /** 当前运行中的 systemd 服务名列表（小写） */
    private List<String> runningUnitNames() {
        try {
            ExecResult exec = commandExecutor.exec(
                "systemctl", "list-units", "--type=service", "--state=running",
                "--no-pager", "--no-legend", "--plain");
            if (exec.getExitCode() != 0) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (String line : exec.getStdout().split("\\r?\\n")) {
                if (!line.isBlank()) {
                    names.add(line.trim().split("\\s+")[0].toLowerCase(Locale.ROOT));
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("Failed to query running units: {}", e.getMessage());
            return List.of();
        }
    }

    /** 运行状态：有关键字则匹配运行单元；无关键字按启用配置视为运行中 */
    private boolean isRunning(List<String> runningUnits, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        return runningUnits.stream()
            .anyMatch(unit -> keywords.stream().anyMatch(unit::contains));
    }

    /** 已知服务表：展示名 -> systemd 单元匹配关键字 */
    private static Map<String, List<String>> buildServiceKeywords() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("GitLab", List.of("gitlab"));
        map.put("Jenkins", List.of("jenkins"));
        map.put("Jellyfin", List.of("jellyfin"));
        map.put("Nginx", List.of("nginx"));
        map.put("Apache", List.of("apache2", "httpd"));
        map.put("Portainer", List.of("portainer"));
        map.put("MinIO", List.of("minio"));
        map.put("Nextcloud", List.of("nextcloud"));
        map.put("Emby", List.of("emby"));
        map.put("Plex", List.of("plexmediaserver"));
        map.put("qBittorrent", List.of("qbittorrent"));
        map.put("Sonarr", List.of("sonarr"));
        map.put("Radarr", List.of("radarr"));
        map.put("Prowlarr", List.of("prowlarr"));
        map.put("Transmission", List.of("transmission"));
        return map;
    }

    /** 将 journalctl 时间转为服务器本地展示时间；解析失败原样返回 */
    private String formatTime(String raw) {
        try {
            String normalized = raw.length() >= 22 && raw.charAt(19) == '+'
                && raw.charAt(22) != ':'
                ? raw.substring(0, 22) + ":" + raw.substring(22)
                : raw;
            OffsetDateTime odt = OffsetDateTime.parse(normalized);
            return odt.atZoneSameInstant(ZoneId.systemDefault()).format(DISPLAY_TIME);
        } catch (Exception e) {
            return raw;
        }
    }
}
