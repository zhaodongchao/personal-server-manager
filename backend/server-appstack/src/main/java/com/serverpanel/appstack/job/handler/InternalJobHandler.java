package com.serverpanel.appstack.job.handler;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.job.JobContext;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.job.JobExecuteResult;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.job.JobSupport;
import com.serverpanel.appstack.job.internal.InternalTaskRegistry;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.job.InternalTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面板内置任务处理器 —— 最安全的一档（无外部命令、无任意网络、参数强类型）。
 *
 * <p>它也是把「表达力」从 GLUE（在线编码，本方案明确不做）那里补回来的主要手段：
 * 运维真正高频需要的那几件事（备份库、清回收站、清日志、续证书）都用受控 bean 实现，
 * 而不是让用户在浏览器里写脚本。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalJobHandler implements JobHandler {

    private final InternalTaskRegistry registry;

    @Override
    public String type() {
        return JobEnums.HANDLER_INTERNAL;
    }

    @Override
    public HandlerSchema schema() {
        List<String> codes = registry.all().stream().map(InternalTask::code).toList();
        String help = "可用内置任务：\n" + registry.all().stream()
                .map(task -> "· " + task.code() + " —— " + task.description())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（无）");
        return new HandlerSchema(type(), "面板内置任务",
                "由面板自身实现的任务，安全可控，无外部命令与任意网络访问",
                List.of(
                        HandlerField.select("task", "内置任务", true, codes, help),
                        HandlerField.area("params", "参数（JSON 对象）", false,
                                "按所选任务要求填写，如 {\"databaseId\":\"123\"}")));
    }

    @Override
    public void validate(Map<String, Object> param, AppJob job) {
        String code = JobSupport.str(param, "task");
        if (code == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "内置任务不能为空");
        }
        if (registry.get(code) == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "内置任务不存在：" + code);
        }
    }

    @Override
    public JobExecuteResult execute(JobContext ctx) {
        String code = JobSupport.str(ctx.param(), "task");
        InternalTask task = registry.get(code);
        if (task == null) {
            return JobExecuteResult.fail("内置任务不存在：" + code);
        }
        Map<String, String> params = JobSupport.strMap(ctx.param(), "params");
        try {
            InternalTask.Result result = task.execute(params);
            return result.success()
                    ? JobExecuteResult.ok(result.message(), result.output())
                    : JobExecuteResult.fail(result.message(), result.output());
        } catch (RuntimeException e) {
            log.warn("内置任务 {} 执行异常：{}", code, e.getMessage());
            return JobExecuteResult.fail("内置任务执行异常：" + e.getMessage());
        }
    }
}
