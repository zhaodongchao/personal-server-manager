package com.serverpanel.ops.constant;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务保护清单 —— 命中清单的服务操作降级为 L3（二次确认 + 影响面提示）。
 *
 * <p>理由：这些单元一旦被误停，会直接切断「面板自身的访问路径」或「宿主代理自身」：
 * <ul>
 *   <li>{@code ssh/sshd} —— 唯一的远程登录通道；</li>
 *   <li>{@code psm-hostagent} —— 面板管理宿主机的唯一通道，停掉后本页也失效；</li>
 *   <li>{@code docker} —— 面板容器自身运行在其上；</li>
 *   <li>{@code nginx} —— 可能是面板/业务站点的入口；</li>
 *   <li>{@code jenkins} —— 发版流水线所在。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
public final class ProtectedUnits {

    /** 内置保护清单（如需扩展改这里；命中即进入 L3） */
    private static final Set<String> PROTECTED = new LinkedHashSet<>(Set.of(
            "ssh.service",
            "sshd.service",
            "ssh.socket",
            "psm-hostagent.service",
            "docker.service",
            "docker.socket",
            "containerd.service",
            "nginx.service",
            "mysql.service",
            "mariadb.service",
            "redis-server.service",
            "redis.service",
            "jenkins.service",
            "fail2ban.service",
            "systemd-logind.service",
            "dbus.service",
            "systemd-networkd.service",
            "networking.service"));

    private ProtectedUnits() {}

    /** 是否受保护 */
    public static boolean isProtected(String unit) {
        return unit != null && PROTECTED.contains(unit);
    }

    /** 全部保护单元（供前端提前提示） */
    public static Set<String> all() {
        return java.util.Collections.unmodifiableSet(PROTECTED);
    }
}
