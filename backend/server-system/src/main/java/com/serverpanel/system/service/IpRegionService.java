package com.serverpanel.system.service;

import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetAddress;

/**
 * IP 归属地离线查询（基于内置 ip2region.xdb）。
 */
@Slf4j
@Service
public class IpRegionService {

    /** 未知/无法解析时的兜底文案 */
    private static final String UNKNOWN = "未知地区";

    private volatile Searcher searcher;

    /**
     * 解析 IP 为地区标签，如 "中国-广东省-深圳市"；内网/本机返回 "内网"。
     */
    public String region(String ip) {
        if (ip == null || ip.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = ip.trim();
        if (isPrivateIp(trimmed)) {
            return "内网";
        }
        Searcher s = getSearcher();
        if (s == null) {
            return UNKNOWN;
        }
        try {
            String result = s.search(trimmed);
            return formatRegion(result);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** ip2region 原始结果形如 "中国|0|广东省|深圳市|电信"，拼出可读地区 */
    private String formatRegion(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String[] parts = raw.split("\\|");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            String seg = parts[i];
            if (seg == null || seg.isBlank() || "0".equals(seg)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('-');
            }
            sb.append(seg);
        }
        return sb.isEmpty() ? UNKNOWN : sb.toString();
    }

    /** 内网/本机/保留地址直接归类，避免查库 */
    private boolean isPrivateIp(String ip) {
        try {
            // IPv6 回环
            if ("::1".equals(ip)) {
                return true;
            }
            InetAddress addr = InetAddress.getByName(ip);
            if (!(addr instanceof java.net.Inet4Address)) {
                return false;
            }
            byte[] b = addr.getAddress();
            int first = b[0] & 0xff;
            // 本机/内网/链路本地/CGNAT
            if (first == 127 || first == 10 || first == 192 && (b[1] & 0xff) == 168
                || first == 169 && (b[1] & 0xff) == 254
                || first == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127) {
                return true;
            }
            if (first == 172) {
                int second = b[1] & 0xff;
                return second >= 16 && second <= 31;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Searcher getSearcher() {
        Searcher s = searcher;
        if (s == null) {
            synchronized (this) {
                if (searcher == null) {
                    searcher = initSearcher();
                }
                s = searcher;
            }
        }
        return s;
    }

    /** 加载 classpath 下的 ip2region.xdb，整体载入内存（查询快且线程安全） */
    private Searcher initSearcher() {
        try (InputStream in = new ClassPathResource("ip2region.xdb").getInputStream()) {
            byte[] data = in.readAllBytes();
            Searcher s = Searcher.newWithBuffer(data);
            log.info("ip2region.xdb loaded, {} bytes", data.length);
            return s;
        } catch (Exception e) {
            log.error("Failed to load ip2region.xdb, region lookup disabled", e);
            return null;
        }
    }
}
