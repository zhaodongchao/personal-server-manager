package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.system.dto.dashboard.QuickService;
import com.serverpanel.system.dto.dashboard.SshLoginInfo;
import com.serverpanel.system.dto.dashboard.VisitSource;
import com.serverpanel.system.entity.SysLoginLog;
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

    /** 已知服务 -> 展示名 / Web 端口 / 路径（用于快捷导航） */
    private static final Map<String, String[]> KNOWN_SERVICES = buildKnownServices();

    private final CommandExecutor commandExecutor;
    private final SysLoginLogMapper loginLogMapper;
    private final IpRegionService ipRegionService;

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
     * 快捷导航：识别运行中的已知 Web 服务（gitlab/jenkins/jellyfin/...）。
     */
    public List<QuickService> quickServices() {
        List<QuickService> result = new ArrayList<>();
        try {
            ExecResult exec = commandExecutor.exec(
                "systemctl", "list-units", "--type=service", "--state=running",
                "--no-pager", "--no-legend", "--plain");
            if (exec.getExitCode() != 0) {
                return result;
            }
            for (String line : exec.getStdout().split("\\r?\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String name = line.trim().split("\\s+")[0];
                String key = matchKnown(name);
                if (key == null) {
                    continue;
                }
                QuickService svc = new QuickService();
                svc.setName(name);
                svc.setDisplayName(KNOWN_SERVICES.get(key)[0]);
                svc.setPort(Integer.parseInt(KNOWN_SERVICES.get(key)[1]));
                svc.setPath(KNOWN_SERVICES.get(key)[2]);
                svc.setRunning(true);
                result.add(svc);
            }
        } catch (Exception e) {
            log.warn("Failed to query quick services: {}", e.getMessage());
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

    /** 服务名包含已知关键字则返回关键字，否则 null（按最长关键字优先） */
    private String matchKnown(String unitName) {
        String lower = unitName.toLowerCase(Locale.ROOT);
        String best = null;
        for (String key : KNOWN_SERVICES.keySet()) {
            if (lower.contains(key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best;
    }

    /** 已知服务表：关键字 -> {展示名, 端口, 路径} */
    private static Map<String, String[]> buildKnownServices() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("gitlab", new String[]{"GitLab", "80", ""});
        map.put("jenkins", new String[]{"Jenkins", "8080", ""});
        map.put("jellyfin", new String[]{"Jellyfin", "8096", ""});
        map.put("nginx", new String[]{"Nginx", "80", ""});
        map.put("apache2", new String[]{"Apache", "80", ""});
        map.put("httpd", new String[]{"Apache", "80", ""});
        map.put("portainer", new String[]{"Portainer", "9000", ""});
        map.put("minio", new String[]{"MinIO", "9001", ""});
        map.put("nextcloud", new String[]{"Nextcloud", "80", ""});
        map.put("emby", new String[]{"Emby", "8096", ""});
        map.put("plexmediaserver", new String[]{"Plex", "32400", ""});
        map.put("qbittorrent", new String[]{"qBittorrent", "8080", ""});
        map.put("sonarr", new String[]{"Sonarr", "8989", ""});
        map.put("radarr", new String[]{"Radarr", "7878", ""});
        map.put("prowlarr", new String[]{"Prowlarr", "9696", ""});
        map.put("transmission", new String[]{"Transmission", "9091", ""});
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
