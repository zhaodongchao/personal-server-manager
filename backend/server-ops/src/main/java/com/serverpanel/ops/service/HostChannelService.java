package com.serverpanel.ops.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.framework.command.HostExecutor;
import com.serverpanel.framework.command.HostResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 宿主通道门面：为运维三模块提供统一的「取通道 / 校验可用性 / 调 op」入口。
 *
 * <p>设计意图：把「通道不可用时怎么办」这一决策集中在一处，业务代码只关心
 * 成功路径；不可用时一律抛出 {@link ErrorCode#HOST_CHANNEL_UNAVAILABLE}，
 * 由全局异常处理器转成 5009，前端据此把页面降级为只读并展示安装指引。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostChannelService {

    private final HostExecutor hostExecutor;

    /** 通道能力快照（带短缓存） */
    public HostCapability capability() {
        return hostExecutor.capability();
    }

    /** 强制重探（安装代理后无需重启面板） */
    public HostCapability refresh() {
        return hostExecutor.probe();
    }

    /** 通道是否可用 */
    public boolean available() {
        return capability().isOk();
    }

    /** 取通道，不可用直接抛 5009 */
    public HostExecutor require() {
        HostCapability capability = capability();
        if (!capability.isOk()) {
            throw new ServiceException(ErrorCode.HOST_CHANNEL_UNAVAILABLE.getCode(),
                    capability.getMessage() == null
                            ? ErrorCode.HOST_CHANNEL_UNAVAILABLE.getMessage()
                            : capability.getMessage());
        }
        return hostExecutor;
    }

    /**
     * 调用宿主 op；通道级失败抛 5009，命令级失败抛 500。
     *
     * @param op    代理白名单内的 op 名
     * @param args  参数
     * @param label 供用户理解的业务名（如「读取服务列表」）
     */
    public HostResult call(String op, Map<String, Object> args, String label) {
        return call(op, args, label, 0L);
    }

    /** 调用宿主 op（指定超时秒数；0 表示用默认超时） */
    public HostResult call(String op, Map<String, Object> args, String label, long timeoutSeconds) {
        HostExecutor executor = require();
        HostResult result = timeoutSeconds > 0
                ? executor.call(op, args, timeoutSeconds)
                : executor.call(op, args);
        if (!result.isOk()) {
            log.warn("宿主通道调用失败：op={} code={} msg={}", op, result.getCode(), result.getMessage());
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                    label + "失败：" + result.errorText());
        }
        return result;
    }

    /** 调用宿主 op 并返回命令原文，退出码非 0 时抛错 */
    public String callText(String op, Map<String, Object> args, String label) {
        HostResult result = call(op, args, label);
        if (result.getExitCode() != 0) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                    label + "失败：" + result.errorText());
        }
        return result.text();
    }

    /** 调用宿主 op 并返回命令原文，允许非 0 退出码（用于 status 这类「非 0 但有效」的输出） */
    public String callTextLenient(String op, Map<String, Object> args, String label) {
        HostResult result = call(op, args, label);
        String text = result.text();
        return text.isBlank() ? result.getStderr() : text;
    }

    /** 调用宿主 op 并返回命令原文（指定超时秒数；0 表示用默认超时），退出码非 0 时抛错 */
    public String callText(String op, Map<String, Object> args, String label, long timeoutSeconds) {
        HostResult result = call(op, args, label, timeoutSeconds);
        if (result.getExitCode() != 0) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                    label + "失败：" + result.errorText());
        }
        return result.text();
    }

    /** 调用宿主 op 并返回命令原文（指定超时秒数；0 表示用默认超时），允许非 0 退出码 */
    public String callTextLenient(String op, Map<String, Object> args, String label, long timeoutSeconds) {
        HostResult result = call(op, args, label, timeoutSeconds);
        String text = result.text();
        return text.isBlank() ? result.getStderr() : text;
    }
}
