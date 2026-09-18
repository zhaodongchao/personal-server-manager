package com.serverpanel.framework.command;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 安全命令执行器 —— 全系统唯一的系统命令出口。
 *
 * <p>安全约束：
 * <ul>
 *   <li>只接受 argv 数组，绝不拼接 shell 字符串（从根上杜绝注入）；</li>
 *   <li>命令名（argv[0]）必须在白名单内，白名单 = 内置表 + 配置扩展项；</li>
 *   <li>单命令超时（默认 30s）后 destroyForcibly；</li>
 *   <li>stdout/stderr 超过上限（默认 2MB）截断，防止内存打爆。</li>
 * </ul>
 */
@Slf4j
@Component
public class CommandExecutor {

    /** 命令执行专用虚拟线程池（每个任务一个虚拟线程） */
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("cmd-exec-", 0).factory());

    /** 内置命令白名单：本面板需要的系统命令（不含任何 shell 解释器） */
    private static final Set<String> BUILTIN_WHITELIST = Set.of(
        "systemctl", "journalctl",
        "nginx",
        "ufw", "firewall-cmd",
        "mysql", "mysqldump", "mysqladmin");

    private final long timeoutSeconds;

    private final long maxOutputBytes;

    /** 合并后的白名单（内置 + 配置扩展） */
    private volatile Set<String> whitelist;

    public CommandExecutor(
            @Value("${serverpanel.command.timeout-seconds:30}") long timeoutSeconds,
            @Value("${serverpanel.command.max-output-bytes:2097152}") long maxOutputBytes,
            @Value("${serverpanel.command.extra-whitelist:}") String extraWhitelist) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        Set<String> merged = new HashSet<>(BUILTIN_WHITELIST);
        if (extraWhitelist != null && !extraWhitelist.isBlank()) {
            for (String cmd : extraWhitelist.split(",")) {
                String trimmed = cmd.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        this.whitelist = Set.copyOf(merged);
        log.info("CommandExecutor initialized, whitelist = {}", this.whitelist);
    }

    /** 是否允许执行该命令名 */
    public boolean isAllowed(String command) {
        return whitelist.contains(command);
    }

    /**
     * 同步执行（argv 数组形式，不经过任何 shell）。
     *
     * @throws ServiceException 命令不在白名单 / 启动失败
     */
    public ExecResult exec(String... argv) {
        if (argv == null || argv.length == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "命令不能为空");
        }
        if (!isAllowed(argv[0])) {
            log.warn("Blocked non-whitelisted command: {}", argv[0]);
            throw new ServiceException(ErrorCode.CMD_NOT_ALLOWED);
        }
        long start = System.currentTimeMillis();
        Process process = null;
        try {
            process = new ProcessBuilder(argv).start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            boolean timedOut = false;
            int exitCode;
            if (!finished) {
                process.destroyForcibly();
                timedOut = true;
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
            return new ExecResult(exitCode, stdout, stderr, timedOut,
                System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.error("Failed to start command: {}", argv[0], e);
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "命令启动失败: " + argv[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new ServiceException(ErrorCode.ERROR.getCode(), "命令执行被中断");
        }
    }

    /**
     * 异步执行（虚拟线程），适合耗时命令与计划任务。
     */
    public CompletableFuture<ExecResult> execAsync(String... argv) {
        return CompletableFuture.supplyAsync(() -> exec(argv), VIRTUAL_EXECUTOR);
    }

    /** 限时读流并按字节上限截断 */
    private String readStream(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            int toTake = (int) Math.min((long) n, maxOutputBytes - total);
            if (toTake > 0) {
                sb.append(new String(buffer, 0, toTake, StandardCharsets.UTF_8));
                total += toTake;
            }
            if (total >= maxOutputBytes) {
                break;
            }
        }
        return sb.toString();
    }

    /** 命令超时时间（供调度器等复用） */
    public Duration getTimeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }
}
