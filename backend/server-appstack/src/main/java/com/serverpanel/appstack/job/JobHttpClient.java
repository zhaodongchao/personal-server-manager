package com.serverpanel.appstack.job;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/**
 * 出站 HTTP 客户端（HTTP 类任务 + 外部执行器回调共用）。
 *
 * <p><b>为什么用 JDK 的 {@link HttpClient} 而不是 spring-web 的 RestClient</b>：
 * 本合同需要三件事 —— 精确的连接/读取超时、不跟随重定向、在 4xx/5xx 时**不抛异常**
 * （要拿状态码与期望值比对）。JDK 客户端三件事都是显式 API（{@code connectTimeout}、
 * {@code Redirect.NEVER}、直接返回 {@code HttpResponse}），且同样零新增依赖 ——
 * 设计里「零新增依赖」的目标不变，实现载体由 RestClient 换成 JDK 原生。
 *
 * <p><b>SSRF 护栏</b>（HTTP 能力是本次唯一新增的攻击面）：
 * scheme 仅 http/https（JDK 客户端本身不支持 file:/gopher:/dict:）；云元数据地址黑名单；
 * 不跟随重定向；连接 5s、读取 {@code min(timeoutSec, 60)}s；响应体上限 1MB。
 *
 * <p>刻意**允许**访问 127.0.0.1 与内网：面板的职责就是管理本机与内网服务，
 * 「禁内网」等于把功能做没。风险用「仅管理员可建任务 + 全量审计 + 元数据地址黑名单」来控制。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
public class JobHttpClient {

    /** 云元数据地址黑名单：这类地址一旦可读，等于把云主机的临时凭据交出去 */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "169.254.169.254",
            "100.100.100.200",
            "fd00:ec2::254",
            "metadata.google.internal");

    /** 响应体上限（与宿主 2MB 上限同思路，独立设限） */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    private static final int MAX_READ_TIMEOUT_SECONDS = 60;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 一次 HTTP 调用的结果。
     *
     * @param status     实际状态码
     * @param body       响应体（已截断）
     * @param durationMs 耗时
     */
    public record Response(int status, String body, long durationMs) {}

    /**
     * 发起请求。
     *
     * @param method      HTTP 方法
     * @param url         完整 URL
     * @param headers     自定义头
     * @param contentType Content-Type（可为空）
     * @param body        请求体（可为空）
     * @param timeoutSec  读取超时秒数（内部再夹到 60s）
     */
    public Response send(String method, String url, Map<String, String> headers,
                         String contentType, String body, int timeoutSec) {
        URI uri = validateUrl(url);
        long seconds = Math.min(Math.max(timeoutSec, 1), MAX_READ_TIMEOUT_SECONDS);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(seconds));
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    builder.header(key, value);
                }
            });
        }
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        String payload = body == null ? "" : body;
        String httpMethod = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        builder.method(httpMethod, payload.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        long started = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long cost = System.currentTimeMillis() - started;
            String text = response.body();
            if (text != null && text.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                text = JobSupport.truncate(text, MAX_RESPONSE_BYTES);
            }
            return new Response(response.statusCode(), text, cost);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                    "HTTP 调用失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(ErrorCode.ERROR.getCode(), "HTTP 调用被中断");
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "HTTP 请求不合法：" + e.getMessage());
        }
    }

    /**
     * URL 安全校验（SSRF 护栏）。
     *
     * @return 解析后的 URI
     * @throws ServiceException 6033 URL 不合法或命中黑名单
     */
    public URI validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(), "URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "URL 格式非法：" + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "只允许 http / https，实际为：" + (scheme.isEmpty() ? "(空)" : scheme));
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(), "URL 缺少主机名");
        }
        if (BLOCKED_HOSTS.contains(host)) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "云元数据地址不允许访问：" + host);
        }
        return uri;
    }
}
