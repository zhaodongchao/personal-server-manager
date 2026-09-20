package com.serverpanel.monitor.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 宿主机文件访问器（容器部署适配）。
 *
 * <p>面板常以容器方式部署（{@code -v /:/host} + {@code HOST_SYSROOT=/host}），
 * 此时容器内的 {@code /proc}、{@code /sys} 只反映容器自身视角。本组件把
 * 「宿主机根前缀」的解析与回退收敛到一处：优先按 {@code <sysroot><path>} 读取，
 * 失败再回退本机路径；裸机 / systemd 部署未配置 sysroot 时直接读本机。
 *
 * <p>监控模块的磁盘、挂载表、网卡等宿主视角采集统一经由此组件访问文件系统。
 *
 * @author zhaodc
 * @since 2026-09-20
 */
@Slf4j
@Component
public class HostFileAccess {

    /**
     * 宿主机根路径前缀（容器部署用，如 {@code /host}）：将宿主机 / 递归挂载进容器后，
     * 通过该前缀读取宿主机 {@code /proc}、{@code /sys}，解决容器内看不到宿主机挂载表、
     * 块设备与网卡的问题。裸机 / systemd 部署留空。
     */
    @Value("${serverpanel.monitor.host-sysroot:}")
    private String hostSysroot;

    /** sysroot 解析结果缓存（见 {@link #sysroot()}） */
    private volatile String sysrootCache;

    /**
     * 宿主机根前缀：显式配置优先；未配置时探测常见宿主机挂载点
     * （{@code docker run -v /:/host} 等），命中则按宿主机视角采集。
     *
     * @return 宿主机根前缀；裸机部署返回空串
     */
    public String sysroot() {
        String cached = sysrootCache;
        if (cached != null) {
            return cached;
        }
        String sr = hostSysroot == null ? "" : hostSysroot.trim();
        while (sr.endsWith("/")) {
            sr = sr.substring(0, sr.length() - 1);
        }
        if (sr.isEmpty()) {
            for (String candidate : new String[] {"/host", "/hostfs", "/mnt/host"}) {
                if (Files.exists(Path.of(candidate, "proc", "mounts"))) {
                    sr = candidate;
                    log.info("探测到宿主机根挂载点 {}，磁盘 / 文件系统 / 网卡将按宿主机视角采集", sr);
                    break;
                }
            }
        }
        sysrootCache = sr;
        return sr;
    }

    /**
     * 读取宿主机文件：容器部署时优先读 sysroot 前缀路径，失败回退本机路径。
     *
     * @param relPath 以 / 开头的绝对路径（如 {@code /proc/net/dev}、{@code /sys/class/net/eth0/mtu}）
     * @return 文件内容；读取失败返回空串
     */
    public String readFile(String relPath) {
        for (String candidate : candidates(relPath)) {
            try {
                String content = Files.readString(Path.of(candidate), StandardCharsets.UTF_8);
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (Exception ignored) {
                // 回退下一个候选路径
            }
        }
        return "";
    }

    /**
     * 判断宿主机路径是否存在（文件或目录）。
     *
     * @param relPath 以 / 开头的绝对路径
     */
    public boolean exists(String relPath) {
        for (String candidate : candidates(relPath)) {
            if (Files.exists(Path.of(candidate), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(Path.of(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列出宿主机目录下的条目名（不带路径），按名称升序。目录不存在或不可读时返回空列表。
     *
     * @param relDir 以 / 开头的绝对目录路径
     */
    public List<String> listNames(String relDir) {
        for (String candidate : candidates(relDir)) {
            try (Stream<Path> stream = Files.list(Path.of(candidate))) {
                List<String> names = stream
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (!names.isEmpty()) {
                    return names;
                }
            } catch (Exception ignored) {
                // 回退下一个候选路径
            }
        }
        return List.of();
    }

    /**
     * 读取软链接的目标末段名（不含路径）。
     *
     * <p>用于解析 sysfs 中的归属关系，例如
     * {@code /sys/class/net/veth0/master -> ../../br-xxx} 得到 {@code br-xxx}，
     * {@code <iface>/device/driver -> ../../../bus/pci/drivers/e1000e} 得到 {@code e1000e}。
     *
     * @param relPath 软链接路径
     * @return 目标末段名；非软链接或读取失败返回空串
     */
    public String linkName(String relPath) {
        for (String candidate : candidates(relPath)) {
            try {
                Path target = Files.readSymbolicLink(Path.of(candidate));
                Path name = target.getFileName();
                if (name != null) {
                    return name.toString();
                }
            } catch (Exception ignored) {
                // 回退下一个候选路径
            }
        }
        return "";
    }

    /**
     * 组装候选物理路径：容器部署时先 sysroot 前缀路径，其后是本机路径作为兜底。
     *
     * @param relPath 以 / 开头的绝对路径
     */
    private List<String> candidates(String relPath) {
        String sr = sysroot();
        if (sr.isEmpty()) {
            return List.of(relPath);
        }
        return List.of(sr + relPath, relPath);
    }
}
