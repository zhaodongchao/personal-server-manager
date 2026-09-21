package com.serverpanel.framework.command;

import java.util.Map;

/**
 * 宿主执行通道抽象 —— 面板访问宿主机系统能力的唯一入口。
 *
 * <p>背景：面板后端运行在容器内（基镜像无 systemctl / journalctl / ufw），而「服务管理、
 * 计划管理、防火墙管理」必须在宿主机上执行系统命令。本接口屏蔽底层实现差异，
 * 使业务代码与通道形态解耦：
 * <ul>
 *   <li>{@code hostagent} —— 宿主机常驻 Python 代理 + AF_UNIX 套接字（目标态，默认）；</li>
 *   <li>{@code nsenter} —— 特权旁车容器（回退方案）；</li>
 *   <li>{@code none} —— 不可用，业务必须降级为只读并给出安装指引。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
public interface HostExecutor {

    /** 通道模式标识 */
    String mode();

    /** 通道当前是否可用（实现方需自行做短缓存，避免每次调用都发起探测） */
    boolean isAvailable();

    /** 通道能力快照（实现方负责短缓存，供页面/接口高频读取） */
    HostCapability capability();

    /** 主动探测宿主能力（绕过缓存） */
    HostCapability probe();

    /** 调用一个宿主 op（使用默认超时） */
    HostResult call(String op, Map<String, Object> args);

    /** 调用一个宿主 op（指定超时秒数） */
    HostResult call(String op, Map<String, Object> args, long timeoutSeconds);
}
