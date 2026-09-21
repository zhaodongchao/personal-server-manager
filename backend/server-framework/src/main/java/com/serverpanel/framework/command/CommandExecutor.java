package com.serverpanel.framework.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/**
 * 安全命令执行器 —— 容器内可直接执行的系统命令出口（进程管理 / 磁盘 / MySQL 等）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>只接受 argv 数组，绝不拼接 shell 字符串（从根上杜绝注入）；</li>
 *   <li>命令名（argv[0]）必须在白名单内，白名单 = 内置表 + 配置扩展项；</li>
 *   <li>单命令超时（默认 30s，可按调用覆盖）后 destroyForcibly；</li>
 *   <li>stdout/stderr 各超过上限（默认 2MB）截断，防止内存打爆。</li>
 * </ul>
 *
 * <p><b>2026-09-21 修复（死锁）</b>：旧实现先串行读完 stdout 再读 stderr，且读流发生在
 * {@code waitFor} 之前。当子进程 stderr 写满 OS 管道缓冲（约 64KB）时，子进程阻塞在写、
 * 父进程阻塞在读 stdout，双方互等形成死锁，只能等超时。现改为 <b>两个虚拟线程并发读</b>
 * 两个流，随后再 waitFor，从根上消除该死锁。
 *
 * <p><b>2026-09-21 增强</b>：新增可覆盖超时的重载 {@link #exec(long, String...)} 等，
 * 供计划任务按自身 timeoutSec 执行（此前 timeoutSec 完全无效）。
 *
 * <p>说明：需要 root 权限的宿主机系统命令（systemctl / journalctl / ufw）在容器部署形态下
 * **不走本类**，而是经 {@link HostExecutor} 通道交由宿主机 psm-hostagent 执行；
 * 本类的 execSudo 仅适用于裸机部署（进程本身具备免密 sudo）。
 */
@Slf4j
@Component
public class CommandExecutor {

    /** 命令执行专用虚拟线程池（每个任务一个虚拟线程） */
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cmd-exec-", 0).factory());

    /** 内置命令白名单：本面板需要的系统命令（不含任何 shell 解释器） */
    private static final Set<String> BUILTIN_WHITELIST = Set.of(
            "ps",
            "kill",
            "systemctl",
            "journalctl",
            "nginx",
            "ufw",
            "firewall-cmd",
            "mysql",
            "mysqldump",
            "mysqladmin",
            "pvs",
            "vgs",
            "lvs",
            "pvdisplay",
            "vgdisplay",
            "lvdisplay",
            "lsblk",
            "fdisk",
            "df",
            "findmnt");

    /** 超时上限，避免调用方传入离谱值把线程占死 */
    private static final long MAX_TIMEOUT_SECONDS = 3600L;

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
     * 同步执行（argv 数组形式，不经过任何 shell），使用默认超时。
     *
     * @throws ServiceException 命令不在白名单 / 启动失败
     */
    public ExecResult exec(String... argv) {
        return execInternal(false, Map.of(), timeoutSeconds, argv);
    }

    /** 同步执行，指定超时秒数（计划任务按 timeoutSec 调用此重载） */
    public ExecResult exec(long timeoutSeconds, String... argv) {
        return execInternal(false, Map.of(), timeoutSeconds, argv);
    }

    /**
     * 以 sudo（免密非交互）执行白名单命令。
     *
     * <p>实际进程为 {@code sudo -n -- <argv...>}：<code>--</code> 分隔符阻断 sudo 选项注入，
     * 业务命令与参数原样透传；白名单校验仍针对底层命令 argv[0]。需免密 sudo 方可成功。
     */
    public ExecResult execSudo(String... argv) {
        return execInternal(true, Map.of(), timeoutSeconds, argv);
    }

    /** 以 sudo 执行，指定超时秒数 */
    public ExecResult execSudo(long timeoutSeconds, String... argv) {
        return execInternal(true, Map.of(), timeoutSeconds, argv);
    }

    /**
     * 同步执行，可附加环境变量（如 MYSQL_PWD）。
     *
     * <p>环境变量用于避免把敏感信息写进 argv（/proc 可见）。
     * 安全约束与 {@link #exec(String...)} 完全一致。
     */
    public ExecResult exec(Map<String, String> env, String... argv) {
        return execInternal(false, env, timeoutSeconds, argv);
    }

    /** 同步执行，附加环境变量并指定超时秒数 */
    public ExecResult exec(Map<String, String> env, long timeoutSeconds, String... argv) {
        return execInternal(false, env, timeoutSeconds, argv);
    }

    private ExecResult execInternal(boolean sudo, Map<String, String> env, long timeout,
                                    String... argv) {
        if (argv == null || argv.length == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "命令不能为空");
        }
        if (!isAllowed(argv[0])) {
            log.warn("Blocked non-whitelisted command: {}", argv[0]);
            throw new ServiceException(ErrorCode.CMD_NOT_ALLOWED);
        }
        long effectiveTimeout = timeout <= 0
                ? timeoutSeconds
                : Math.min(timeout, MAX_TIMEOUT_SECONDS);
        long start = System.currentTimeMillis();
        Process process = null;
        try {
            String[] cmd = argv;
            if (sudo) {
                cmd = new String[argv.length + 3];
                cmd[0] = "sudo";
                cmd[1] = "-n";
                cmd[2] = "--";
                System.arraycopy(argv, 0, cmd, 3, argv.length);
            }
            ProcessBuilder builder = new ProcessBuilder(cmd);
            if (env != null && !env.isEmpty()) {
                builder.environment().putAll(env);
            }
            process = builder.start();

            // 关键修复：两个流并发消费。旧实现串行读流 + 先读后 wait，会在 stderr 写满
            // 管道缓冲时与子进程互相阻塞（经典 Process 死锁）。
            final Process running = process;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStreamQuietly(running.getInputStream()), VIRTUAL_EXECUTOR);
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStreamQuietly(running.getErrorStream()), VIRTUAL_EXECUTOR);

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            boolean timedOut = false;
            int exitCode;
            if (!finished) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
            String stdout = awaitStream(stdoutFuture);
            String stderr = awaitStream(stderrFuture);
            return new ExecResult(exitCode, stdout, stderr, timedOut,
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.error("Failed to start command: {}", argv[0], e);
            throw new ServiceException(ErrorCode.ERROR.getCode(), "命令启动失败: " + argv[0]);
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
     *
     * <p>注意：本重载接受「超时秒数」，此前不存在该参数，导致计划任务的 timeoutSec 形同装饰。
     */
    public CompletableFuture<ExecResult> execAsync(long timeoutSeconds, String... argv) {
        return CompletableFuture.supplyAsync(
                () -> execInternal(false, Map.of(), timeoutSeconds, argv), VIRTUAL_EXECUTOR);
    }

    /** 异步执行（使用默认超时） */
    public CompletableFuture<ExecResult> execAsync(String... argv) {
        return execAsync(timeoutSeconds, argv);
    }

    /** 读流失败不抛异常，返回已读内容 */
    private String readStreamQuietly(InputStream in) {
        try {
            return readStream(in);
        } catch (IOException e) {
            log.debug("命令输出流读取异常: {}", e.getMessage());
            return "";
        }
    }

    /** 等待并发读流结束（进程已结束，流会随管道关闭而结束） */
    private String awaitStream(CompletableFuture<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
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

    /** 默认超时秒数 */
    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
