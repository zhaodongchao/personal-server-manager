package com.serverpanel.framework.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 宿主代理（psm-hostagent）通道实现。
 *
 * <p>通信方式：AF_UNIX 域套接字单行 JSON 请求 / 响应；**不经过任何 TCP 端口**。
 * 鉴权：读取挂载进来的共享密钥文件（0400，只读挂载），随请求发送，代理侧用
 * {@code hmac.compare_digest} 恒定时比较。
 *
 * <p>容错策略：任何 IO 异常都不抛出到业务层，而是返回 {@code ok=false} 的
 * {@link HostResult}，由调用方决定降级行为；能力探测结果带 15s 短缓存，
 * 避免每个页面请求都去打一次探测。
 *
 * <p>序列化说明：本项目是 Spring Boot 4，默认 JSON 栈为 <b>Jackson 3（tools.jackson）</b>，
 * 故此处注入 {@link tools.jackson.databind.ObjectMapper}（容器中确实存在该 bean）。
 * 勿改用 {@code com.fasterxml.jackson}（其在容器内无对应 bean，会导致启动失败）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Slf4j
@Component
public class HostAgentExecutor implements HostExecutor {

    /** 代理单次调用的 socket 读写跑在虚拟线程上，主线程按超时等待 */
    private static final ExecutorService SOCKET_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("hostagent-", 0).factory());

    /** 能力探测缓存时长（毫秒） */
    private static final long CAPABILITY_TTL_MS = 15_000L;

    /** 单次响应体上限（与代理侧 2MB 输出上限匹配，再留余量） */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String socketPath;

    private final String secretFile;

    private final long connectTimeoutMs;

    private final long defaultTimeoutSeconds;

    private final long maxTimeoutSeconds;

    private final ObjectMapper objectMapper;

    private volatile HostCapability cachedCapability;

    private volatile long capabilityCheckedAt;

    public HostAgentExecutor(
            @Value("${serverpanel.host-agent.socket-path:/run/psm-hostagent/agent.sock}") String socketPath,
            @Value("${serverpanel.host-agent.secret-file:/etc/psm-hostagent/secret}") String secretFile,
            @Value("${serverpanel.host-agent.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${serverpanel.host-agent.default-timeout-seconds:30}") long defaultTimeoutSeconds,
            @Value("${serverpanel.host-agent.max-timeout-seconds:900}") long maxTimeoutSeconds,
            ObjectMapper objectMapper) {
        this.socketPath = socketPath;
        this.secretFile = secretFile;
        this.connectTimeoutMs = connectTimeoutMs;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
        this.objectMapper = objectMapper;
        log.info("HostAgentExecutor 初始化：socket={} secret={}", socketPath, secretFile);
    }

    @Override
    public String mode() {
        return "hostagent";
    }

    @Override
    public boolean isAvailable() {
        return capability().isOk();
    }

    @Override
    public HostCapability capability() {
        HostCapability cached = cachedCapability;
        if (cached != null && System.currentTimeMillis() - capabilityCheckedAt < CAPABILITY_TTL_MS) {
            return cached;
        }
        return probe();
    }

    @Override
    public HostCapability probe() {
        HostCapability capability = new HostCapability();
        capability.setCheckedAt(System.currentTimeMillis());
        capability.setSocketPath(socketPath);
        capability.setInstallHint(installHint());
        capability.setMissing(List.of());
        capability.setTools(Map.of());

        String secret = readSecret();
        if (secret == null) {
            capability.setOk(false);
            capability.setMessage("未找到宿主代理密钥文件（" + secretFile
                    + "），面板无法访问宿主机系统能力");
            cache(capability);
            return capability;
        }
        if (!Files.exists(Path.of(socketPath))) {
            capability.setOk(false);
            capability.setMessage("宿主代理套接字不存在（" + socketPath + "），未安装或未启动 psm-hostagent");
            cache(capability);
            return capability;
        }

        HostResult result = doCall("host.probe", Map.of(), secret, connectTimeoutMs + 10_000L);
        if (!result.isOk()) {
            capability.setOk(false);
            capability.setMessage(result.getCode() == null
                    ? "宿主代理调用失败：" + result.errorText()
                    : "宿主代理拒绝请求（" + result.getCode() + "）：" + result.errorText());
            cache(capability);
            return capability;
        }

        capability.setMode("hostagent");
        capability.setProtocol(intOrZero(result.dataInt("protocol")));
        capability.setAgentVersion(result.dataString("agentVersion"));
        capability.setOs(result.dataString("os"));
        capability.setKernel(result.dataString("kernel"));
        capability.setSystemRunning(result.dataString("systemRunning"));
        capability.setFirewallBackend(result.dataString("firewallBackend"));
        capability.setTools(result.dataStringMap("tools"));
        capability.setMissing(result.dataStringList("missing"));
        capability.setUfwAvailable(capability.getTools().containsKey("ufw"));
        if (!capability.protocolCompatible()) {
            capability.setOk(false);
            capability.setMessage("宿主代理协议版本不匹配（代理 " + capability.getProtocol()
                    + "，面板要求 " + HostCapability.REQUIRED_PROTOCOL + "），已降级为只读");
        } else {
            capability.setOk(true);
            capability.setMessage("宿主通道正常");
        }
        cache(capability);
        return capability;
    }

    @Override
    public HostResult call(String op, Map<String, Object> args) {
        return call(op, args, defaultTimeoutSeconds);
    }

    @Override
    public HostResult call(String op, Map<String, Object> args, long timeoutSeconds) {
        String secret = readSecret();
        if (secret == null) {
            return HostResult.failure("no-secret",
                    "未找到宿主代理密钥文件（" + secretFile + "）", op);
        }
        long timeout = Math.max(1, Math.min(timeoutSeconds, maxTimeoutSeconds));
        return doCall(op, args == null ? Map.of() : args, secret,
                timeout * 1000L + connectTimeoutMs + 5_000L);
    }

    /** 一次完整的请求 / 响应往返 */
    private HostResult doCall(String op, Map<String, Object> args, String secret, long timeoutMs) {
        String request;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", op);
            body.put("secret", secret);
            body.put("args", args);
            request = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return HostResult.failure("encode-error", "请求序列化失败: " + e.getMessage(), op);
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> roundTrip(request), SOCKET_EXECUTOR);
        String responseLine;
        try {
            responseLine = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("宿主代理调用超时：op={} timeoutMs={}", op, timeoutMs);
            return HostResult.failure("timeout", "宿主代理调用超时（" + (timeoutMs / 1000) + "s）", op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HostResult.failure("interrupted", "宿主代理调用被中断", op);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("宿主代理调用失败：op={} err={}", op, cause.toString());
            return HostResult.failure("io-error", "无法连接宿主代理：" + cause.getMessage(), op);
        }

        try {
            HostResult result = objectMapper.readValue(responseLine, HostResult.class);
            if (result.getOp() == null) {
                result.setOp(op);
            }
            return result;
        } catch (Exception e) {
            log.warn("宿主代理响应解析失败：op={} err={} body={}", op, e.getMessage(),
                    abbreviate(responseLine));
            return HostResult.failure("bad-response", "宿主代理响应格式异常", op);
        }
    }

    /** 在 AF_UNIX 套接字上写一行 JSON 并读回一行 JSON */
    private String roundTrip(String requestLine) {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            ByteBuffer payload = ByteBuffer.wrap(
                    (requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            while (payload.hasRemaining()) {
                channel.write(payload);
            }
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            ByteArrayOutputStream acc = new ByteArrayOutputStream(16 * 1024);
            while (acc.size() < MAX_RESPONSE_BYTES) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                byte[] chunk = new byte[read];
                buffer.get(chunk);
                acc.write(chunk);
                if (chunk[read - 1] == '\n') {
                    break;
                }
            }
            return acc.toString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** 读取共享密钥；不存在或不可读返回 null */
    private String readSecret() {
        try {
            Path path = Path.of(secretFile);
            if (!Files.isReadable(path)) {
                return null;
            }
            String secret = Files.readString(path, StandardCharsets.UTF_8).trim();
            return secret.isEmpty() ? null : secret;
        } catch (IOException e) {
            log.debug("读取宿主代理密钥失败: {}", e.getMessage());
            return null;
        }
    }

    private void cache(HostCapability capability) {
        cachedCapability = capability;
        capabilityCheckedAt = System.currentTimeMillis();
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 安装指引（前端直接展示给使用者） */
    private String installHint() {
        return "在宿主机执行仓库内 ops/hostagent/install.sh 安装宿主代理，"
                + "并确保面板容器挂载 /run/psm-hostagent 与 /etc/psm-hostagent:ro";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** 供子类/测试覆盖的可用 op 列表（当前仅作文档用途） */
    public static List<String> knownOps() {
        return new ArrayList<>(List.of(
                "host.ping", "host.probe", "host.info", "host.listenPorts", "host.sshdPorts",
                "service.listUnitFiles", "service.listUnits", "service.failed", "service.show",
                "service.status", "service.cat", "service.isActive", "service.logs",
                "service.action", "service.daemonReload",
                "firewall.statusRaw", "firewall.raw", "firewall.addRule", "firewall.deleteRule",
                "firewall.deleteByNo", "firewall.setEnabled", "firewall.setDefault",
                "firewall.reload", "firewall.version",
                "watchdog.arm", "watchdog.status", "watchdog.list", "watchdog.confirm"));
    }
}
