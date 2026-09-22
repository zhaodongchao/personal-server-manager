package com.serverpanel.appstack.job.handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.job.JobChannel;
import com.serverpanel.appstack.job.JobContext;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.job.JobExecuteResult;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.job.JobSupport;
import com.serverpanel.common.constant.ProtectedUnits;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.job.JobField;
import com.serverpanel.framework.command.HostResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * systemd 服务动作处理器。
 *
 * <p><b>与服务保护清单联动</b>：命中 {@link ProtectedUnits} 且动作为破坏性
 * （stop / restart / try-restart / disable / mask / kill）时，任务必须携带
 * {@code APPLY <unit>} 形式的确认关键字，且**每次执行前都要再校验一次**。
 *
 * <p>理由很直接：清单里躺着 {@code ssh.service} / {@code psm-hostagent.service} /
 * {@code docker.service}。一个定时任务 {@code restart ssh.service} 就是一次自杀式自锁 ——
 * 而且在「凌晨三点自动执行」这个语境下，它比手动误点危险得多。这类任务必须与
 * 运维工具里的服务管理用同一把尺子。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceJobHandler implements JobHandler {

    /** 服务单元名白名单正则（与 hostagent 的 valid_unit 同源约束） */
    private static final Pattern UNIT_NAME = Pattern.compile("^[A-Za-z0-9@._:-]+$");

    private final JobChannel jobChannel;

    @Override
    public String type() {
        return JobEnums.HANDLER_SERVICE;
    }

    @Override
    public HandlerSchema schema() {
        return new HandlerSchema(type(), "systemd 服务动作",
                "定时对指定 systemd 服务执行 start/stop/restart/reload 等动作",
                List.of(
                        JobField.text("unit", "服务单元", true,
                                "如 nginx.service；命中服务保护清单的破坏性动作需确认关键字"),
                        JobField.select("action", "动作", true,
                                JobEnums.SERVICE_ACTIONS.stream().sorted().toList(),
                                "stop/restart/disable/mask/kill 属破坏性动作")));
    }

    @Override
    public void validate(Map<String, Object> param, AppJob job) {
        String unit = JobSupport.str(param, "unit");
        String action = JobSupport.str(param, "action");
        if (unit == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "服务单元不能为空");
        }
        if (!UNIT_NAME.matcher(unit).matches()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "服务单元名不合法：" + unit);
        }
        if (action == null || !JobEnums.SERVICE_ACTIONS.contains(action)) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "不支持的服务动作：" + action);
        }
        String problem = confirmProblem(unit, action, job == null ? null : job.getConfirmKeyword());
        if (problem != null) {
            throw new ServiceException(ErrorCode.JOB_CONFIRM_REQUIRED, problem);
        }
    }

    @Override
    public JobExecuteResult execute(JobContext ctx) {
        String unit = JobSupport.str(ctx.param(), "unit");
        String action = JobSupport.str(ctx.param(), "action");
        String confirm = ctx.job().getConfirmKeyword();

        // 执行前再校验一次：任务保存之后，保护清单或动作语义都可能被调整
        String problem = confirmProblem(unit, action, confirm);
        if (problem != null) {
            return JobExecuteResult.fail(problem);
        }

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("name", unit);
        call.put("action", action);
        try {
            HostResult result = jobChannel.call("service.action", call, "执行服务动作", ctx.timeoutSec());
            StringBuilder output = new StringBuilder()
                    .append("systemctl ").append(action).append(' ').append(unit).append('\n');
            if (result.getStdout() != null && !result.getStdout().isBlank()) {
                output.append(result.getStdout());
            }
            if (result.getStderr() != null && !result.getStderr().isBlank()) {
                output.append("---- stderr ----\n").append(result.getStderr());
            }
            boolean ok = result.getExitCode() == 0;
            return ok
                    ? JobExecuteResult.ok("systemctl " + action + " " + unit + " 执行成功", output.toString())
                    : JobExecuteResult.fail("systemctl " + action + " " + unit
                            + " 失败（exit=" + result.getExitCode() + "）", output.toString());
        } catch (ServiceException e) {
            return JobExecuteResult.fail(e.getMessage());
        } catch (RuntimeException e) {
            return JobExecuteResult.fail("服务动作执行异常：" + e.getMessage());
        }
    }

    /**
     * 保护清单校验。
     *
     * @return null 表示通过；否则返回给用户看的错误提示
     */
    private String confirmProblem(String unit, String action, String confirmKeyword) {
        if (unit == null || action == null) {
            return "服务单元与动作都不能为空";
        }
        if (!JobEnums.isDestructiveServiceAction(action) || !ProtectedUnits.isProtected(unit)) {
            return null;
        }
        String expected = JobSupport.confirmKeywordFor(unit);
        if (confirmKeyword == null || !expected.equals(confirmKeyword.trim())) {
            return "「" + unit + "」在服务保护清单内，执行 " + action
                    + " 可能导致面板或服务器自身失联，需填写确认关键字：" + expected;
        }
        return null;
    }
}
