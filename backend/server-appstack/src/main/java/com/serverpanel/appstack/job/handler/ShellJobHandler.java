package com.serverpanel.appstack.job.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.config.MysqlAdminProperties;
import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.job.JobChannel;
import com.serverpanel.appstack.job.JobContext;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.job.JobExecuteResult;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.job.JobSupport;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.job.JobField;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.HostResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 宿主 Shell 命令处理器（白名单档）。
 *
 * <p>三层校验，任何一层都足以单独拦住一次越权执行：
 * <ol>
 *   <li>前端：命令名从 {@code GET /appstack/job/commands} 下拉选择，不提供自由输入框；</li>
 *   <li>后端：{@link CommandExecutor#isAllowed} —— 与前端同源的白名单常量；</li>
 *   <li>宿主：代理侧 {@code EXEC_WHITELIST} + 绝对路径解析 + argv ≤ 64。</li>
 * </ol>
 *
 * <p><b>明确否决的做法</b>（不给「变通空间」）：不接受命令整串（只接受命令名 + 参数数组）；
 * 命令名不允许含路径分隔符；不允许 {@code sh} / {@code bash} / {@code env} / {@code xargs} /
 * {@code python} 等解释器进入白名单（现有白名单已满足，此处注释锁死禁止后续放宽）；
 * 除白名单模板变量外不提供任何拼接能力。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShellJobHandler implements JobHandler {

    /** 命令名不得含路径分隔符：只允许白名单里的逻辑命令名 */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("[/\\\\]");

    /** 宿主侧 argv 上限（与 hostagent 实现一致），后端前置同值校验 */
    private static final int MAX_ARGV = 64;

    private final CommandExecutor commandExecutor;

    private final JobChannel jobChannel;

    private final MysqlAdminProperties mysqlAdminProperties;

    @Override
    public String type() {
        return JobEnums.HANDLER_SHELL;
    }

    @Override
    public HandlerSchema schema() {
        return new HandlerSchema(type(), "宿主 Shell 命令",
                "在宿主机上执行白名单命令（argv 数组，绝不经过 shell）",
                List.of(
                        JobField.select("command", "命令", true,
                                commandExecutor.getWhitelist().stream().sorted().toList(),
                                "只允许白名单内的命令名，不接受路径或命令整串"),
                        JobField.area("args", "参数（每行一个）", false,
                                "每个参数独占一行，不做 shell 解析，也不做引号处理"),
                        JobField.number("timeoutSec", "超时（秒）", false,
                                "留空则使用任务级超时")));
    }

    @Override
    public void validate(Map<String, Object> param, AppJob job) {
        String command = JobSupport.str(param, "command");
        if (command == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "命令不能为空");
        }
        if (PATH_SEPARATOR.matcher(command).find()) {
            throw new ServiceException(ErrorCode.JOB_SHELL_NOT_ALLOWED,
                    "命令名不允许包含路径分隔符，请使用白名单中的命令名：" + command);
        }
        if (!commandExecutor.isAllowed(command)) {
            throw new ServiceException(ErrorCode.JOB_SHELL_NOT_ALLOWED,
                    "命令不在白名单内，不允许执行：" + command);
        }
        List<String> args = JobSupport.strList(param, "args");
        if (args.size() + 1 > MAX_ARGV) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "参数过多（含命令名上限 " + MAX_ARGV + " 个）");
        }
    }

    @Override
    public JobExecuteResult execute(JobContext ctx) {
        String command = JobSupport.str(ctx.param(), "command");
        if (command == null) {
            return JobExecuteResult.fail("命令不能为空");
        }
        if (PATH_SEPARATOR.matcher(command).find()) {
            return JobExecuteResult.fail("命令名不允许包含路径分隔符：" + command);
        }
        if (!commandExecutor.isAllowed(command)) {
            return JobExecuteResult.fail("命令不在白名单内：" + command);
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("MYSQL_PWD", mysqlAdminProperties.getPassword() == null
                ? "" : mysqlAdminProperties.getPassword());
        vars.put("JOB_ID", String.valueOf(ctx.jobId()));
        vars.put("JOB_NAME", ctx.jobName() == null ? "" : ctx.jobName());
        vars.put("LOG_ID", String.valueOf(ctx.logId()));

        List<String> argv = new ArrayList<>();
        argv.add(command);
        for (String arg : JobSupport.strList(ctx.param(), "args")) {
            argv.add(JobSupport.applyVars(arg, vars));
        }
        if (argv.size() > MAX_ARGV) {
            return JobExecuteResult.fail("参数过多（含命令名上限 " + MAX_ARGV + " 个）");
        }

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("argv", argv);
        call.put("timeout", ctx.timeoutSec());
        try {
            HostResult result = jobChannel.call("host.exec", call, "执行宿主命令", ctx.timeoutSec());
            String output = describe(result, argv);
            boolean ok = result.getExitCode() == 0 && !result.isTimedOut();
            return ok
                    ? JobExecuteResult.ok("命令执行成功（exit=0）", output)
                    : JobExecuteResult.fail("命令执行失败（exit=" + result.getExitCode()
                            + (result.isTimedOut() ? "，宿主侧超时已终止进程" : "") + "）", output);
        } catch (ServiceException e) {
            return JobExecuteResult.fail(e.getMessage());
        } catch (RuntimeException e) {
            return JobExecuteResult.fail("命令执行异常：" + e.getMessage());
        }
    }

    private String describe(HostResult result, List<String> argv) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(String.join(" ", argv)).append('\n');
        if (result.getStdout() != null && !result.getStdout().isBlank()) {
            sb.append(result.getStdout());
        }
        if (result.getStderr() != null && !result.getStderr().isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("---- stderr ----\n").append(result.getStderr());
        }
        if (result.isTruncated()) {
            sb.append("\n[宿主侧输出超过 2MB，已截断]");
        }
        return sb.toString();
    }
}
