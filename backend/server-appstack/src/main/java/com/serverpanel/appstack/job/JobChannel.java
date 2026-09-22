package com.serverpanel.appstack.job;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostCapability;
import com.serverpanel.framework.command.HostExecutor;
import com.serverpanel.framework.command.HostResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 宿主通道门面（应用栈侧）。
 *
 * <p><b>为什么不复用 {@code ops} 的 {@code HostChannelService}</b>：它在
 * {@code server-ops} 模块里，而 {@code AGENTS.md} 禁止业务模块之间横向依赖
 * （应用栈不该为了调一个宿主 op 去依赖运维工具模块）。这里直接面向
 * {@code server-framework} 的 {@link HostExecutor} 抽象做同一件事 ——
 * 语义与 {@code HostChannelService} 保持一致：通道不可用抛 5009。
 *
 * <p>本模块**不新增任何宿主 op**：Shell 类任务直接复用既有的 {@code host.exec}
 * （其 EXEC_WHITELIST 与后端 {@code CommandExecutor} 逐字一致），服务动作类任务复用
 * {@code service.action}。宿主代理、协议版本、安装脚本本次零改动（设计 ADR-5）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobChannel {

    private final HostExecutor hostExecutor;

    /** 通道能力快照（带短缓存） */
    public HostCapability capability() {
        return hostExecutor.capability();
    }

    /** 通道是否可用 */
    public boolean available() {
        return hostExecutor.capability().isOk();
    }

    /**
     * 调用宿主 op。
     *
     * @param op            代理白名单内的 op 名（本模块只用 host.exec / service.action）
     * @param args          参数
     * @param label         业务标签（拼进错误信息）
     * @param timeoutSeconds 超时秒数（0 表示用默认）
     * @throws ServiceException 通道不可用（5009）或 op 返回失败
     */
    public HostResult call(String op, Map<String, Object> args, String label, long timeoutSeconds) {
        HostCapability capability = hostExecutor.capability();
        if (!capability.isOk()) {
            throw new ServiceException(ErrorCode.HOST_CHANNEL_UNAVAILABLE.getCode(),
                    capability.getMessage() == null
                            ? ErrorCode.HOST_CHANNEL_UNAVAILABLE.getMessage()
                            : capability.getMessage());
        }
        HostResult result = timeoutSeconds > 0
                ? hostExecutor.call(op, args, timeoutSeconds)
                : hostExecutor.call(op, args);
        if (!result.isOk()) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                    label + "失败：" + result.errorText());
        }
        return result;
    }
}
