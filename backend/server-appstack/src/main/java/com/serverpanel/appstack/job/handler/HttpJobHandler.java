package com.serverpanel.appstack.job.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.entity.AppJob;
import com.serverpanel.appstack.job.JobContext;
import com.serverpanel.appstack.job.JobEnums;
import com.serverpanel.appstack.job.JobExecuteResult;
import com.serverpanel.appstack.job.JobHandler;
import com.serverpanel.appstack.job.JobHttpClient;
import com.serverpanel.appstack.job.JobSupport;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.job.JobField;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP 接口调用处理器。
 *
 * <p>成功判定 = 实际状态码 == {@code expectStatus}（默认 200），而不是「2xx 就算成功」——
 * 很多被调方会用 202/204 表示「受理但未完成」，让使用者显式声明期望值才不会有歧义。
 *
 * <p>SSRF 护栏见 {@link JobHttpClient#validateUrl(String)}。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpJobHandler implements JobHandler {

    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

    private final JobHttpClient httpClient;

    @Override
    public String type() {
        return JobEnums.HANDLER_HTTP;
    }

    @Override
    public HandlerSchema schema() {
        return new HandlerSchema(type(), "HTTP 接口调用",
                "定时调用 HTTP 接口，按期望状态码判定成败",
                List.of(
                        JobField.select("method", "方法", true,
                                List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"), null),
                        JobField.text("url", "URL", true,
                                "仅 http/https；云元数据地址会被拒绝；允许本机与内网地址"),
                        JobField.area("headers", "请求头（JSON 对象）", false,
                                "如 {\"X-Token\":\"abc\"}"),
                        JobField.text("contentType", "Content-Type", false,
                                "默认 application/json"),
                        JobField.area("body", "请求体", false, null),
                        JobField.number("expectStatus", "期望状态码", false,
                                "默认 200；与实际不符即判失败")));
    }

    @Override
    public void validate(Map<String, Object> param, AppJob job) {
        String method = JobSupport.str(param, "method");
        if (method == null) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "HTTP 方法不能为空");
        }
        if (!METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID, "不支持的 HTTP 方法：" + method);
        }
        // URL 校验自带 SSRF 语义（协议、云元数据地址），直接透传其错误码，不要包成 6033
        httpClient.validateUrl(JobSupport.str(param, "url"));
        int expectStatus = JobSupport.intOf(param, "expectStatus", 200);
        if (expectStatus < 100 || expectStatus > 599) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID,
                    "期望状态码需在 100~599 之间，实际：" + expectStatus);
        }
    }

    @Override
    public JobExecuteResult execute(JobContext ctx) {
        String method = JobSupport.str(ctx.param(), "method");
        String url = JobSupport.str(ctx.param(), "url");
        Map<String, String> headers = JobSupport.strMap(ctx.param(), "headers");
        String contentType = JobSupport.str(ctx.param(), "contentType");
        String body = JobSupport.str(ctx.param(), "body");
        int expectStatus = JobSupport.intOf(ctx.param(), "expectStatus", 200);
        try {
            JobHttpClient.Response response = httpClient.send(method, url, headers,
                    contentType == null ? "application/json" : contentType,
                    body, ctx.timeoutSec());
            String output = method + " " + url + "\nHTTP " + response.status()
                    + "（" + response.durationMs() + "ms）\n"
                    + (response.body() == null ? "" : response.body());
            return response.status() == expectStatus
                    ? JobExecuteResult.ok("HTTP " + response.status() + "，与期望一致", output)
                    : JobExecuteResult.fail("HTTP " + response.status()
                            + "，期望 " + expectStatus, output);
        } catch (ServiceException e) {
            return JobExecuteResult.fail(e.getMessage());
        } catch (RuntimeException e) {
            return JobExecuteResult.fail("HTTP 调用异常：" + e.getMessage());
        }
    }
}
