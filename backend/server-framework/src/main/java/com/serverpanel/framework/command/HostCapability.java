package com.serverpanel.framework.command;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 宿主执行通道能力快照。
 *
 * <p>面板启动后（以及安装宿主代理后）用它判断：通道是否可用、用的是哪种模式、
 * 宿主侧缺哪些命令。不可用时前端必须**降级为只读并给出安装指引**，不允许静默失败。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class HostCapability {

    /** 通道是否可用（能成功调用 host.probe） */
    private boolean ok;

    /** 通道模式：hostagent（宿主代理） / nsenter（特权旁车，回退方案） / none（不可用） */
    private String mode = "none";

    /** 协议版本，面板与代理不匹配时降级只读 */
    private int protocol;

    /** 代理版本号 */
    private String agentVersion;

    /** 宿主系统，如 Debian GNU/Linux 12 (bookworm) */
    private String os;

    private String kernel;

    /** systemctl is-system-running，如 running / degraded */
    private String systemRunning;

    /** 宿主防火墙后端：ufw / firewalld / none */
    private String firewallBackend;

    /** ufw 是否可用 */
    private boolean ufwAvailable;

    /** 可用命令 → 绝对路径 */
    private Map<String, String> tools;

    /** 缺失命令清单 */
    private List<String> missing;

    /** 套接字路径 */
    private String socketPath;

    /** 不可用时的原因描述 */
    private String message;

    /** 不可用时的安装指引（前端直接展示） */
    private String installHint;

    /** 本快照采集时间（毫秒时间戳） */
    private long checkedAt;

    /** 面板要求的最低协议版本 */
    public static final int REQUIRED_PROTOCOL = 1;

    /** 协议是否兼容 */
    public boolean protocolCompatible() {
        return protocol == REQUIRED_PROTOCOL;
    }
}
