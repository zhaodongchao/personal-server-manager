package com.serverpanel.framework.command;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;

/**
 * 宿主代理调用结果。
 *
 * <p>与代理协议一一对应：代理对每个请求返回一行 JSON，形如
 * <pre>
 * {"ok":true,"exitCode":0,"stdout":"...","stderr":"","timedOut":false,
 *  "truncated":false,"durationMs":12,"data":{...},"op":"service.failed"}
 * </pre>
 * 读取类 op 用 {@link #stdout} 承载命令原文；结构化 op（probe / watchdog 等）用
 * {@link #data} 承载业务负载。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class HostResult {

    /** 代理是否正常处理了本次请求（false 表示鉴权失败/参数非法/op 不存在等） */
    private boolean ok;

    /** ok=false 时的错误码：unauthorized / bad-request / unknown-op / tool-missing / internal */
    private String code;

    /** ok=false 时的错误描述 */
    private String message;

    /** 底层命令退出码；无命令时为 0 */
    private int exitCode;

    private String stdout;

    private String stderr;

    private boolean timedOut;

    /** 输出是否被截断（超过 2MB 上限） */
    private boolean truncated;

    private long durationMs;

    /** 结构化负载 */
    private JsonNode data;

    /** 调用的 op 名 */
    private String op;

    /** 端到端是否可视为成功 */
    public boolean isSuccess() {
        return ok && exitCode == 0 && !timedOut;
    }

    /** 命令标准输出（空安全） */
    public String text() {
        return stdout == null ? "" : stdout;
    }

    /** 取一条适合展示给用户的错误信息 */
    public String errorText() {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        if (stderr != null && !stderr.isBlank()) {
            return stderr.trim();
        }
        if (stdout != null && !stdout.isBlank()) {
            return stdout.trim();
        }
        return "宿主代理调用失败";
    }

    /** 转成通用命令结果，便于复用既有解析逻辑 */
    public ExecResult toExecResult() {
        return new ExecResult(exitCode, stdout, stderr, timedOut, durationMs);
    }

    /** data 节点下的字符串字段 */
    public String dataString(String field) {
        if (data == null) {
            return null;
        }
        JsonNode node = data.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** data 节点下的整型字段 */
    public Integer dataInt(String field) {
        if (data == null) {
            return null;
        }
        JsonNode node = data.get(field);
        return node == null || !node.isNumber() ? null : node.asInt();
    }

    /** data 节点下的布尔字段 */
    public boolean dataBool(String field) {
        if (data == null) {
            return false;
        }
        JsonNode node = data.get(field);
        return node != null && node.asBoolean(false);
    }

    /** data 节点下的列表字段 */
    public JsonNode dataList(String field) {
        if (data == null) {
            return null;
        }
        JsonNode node = data.get(field);
        return node == null || !node.isArray() ? null : node;
    }

    /** 便捷构造：以既有命令结果包装 */
    public static HostResult of(ExecResult exec, String op) {
        HostResult result = new HostResult();
        result.setOk(true);
        result.setExitCode(exec.getExitCode());
        result.setStdout(exec.getStdout());
        result.setStderr(exec.getStderr());
        result.setTimedOut(exec.isTimedOut());
        result.setDurationMs(exec.getDurationMs());
        result.setOp(op);
        return result;
    }

    /** 便捷构造：失败结果 */
    public static HostResult failure(String code, String message, String op) {
        HostResult result = new HostResult();
        result.setOk(false);
        result.setCode(code);
        result.setMessage(message);
        result.setOp(op);
        return result;
    }

    /** 空 args 的便捷入口 */
    public static Map<String, Object> noArgs() {
        return Map.of();
    }
}
