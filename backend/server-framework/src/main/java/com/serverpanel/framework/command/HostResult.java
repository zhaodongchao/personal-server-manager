package com.serverpanel.framework.command;

import java.util.List;
import java.util.Map;

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
 * <p>注意：{@link #data} 刻意声明为 {@code Map<String, Object>} 而非具体 JSON 节点类型。
 * 本项目为 Spring Boot 4，序列化栈是 <b>Jackson 3（tools.jackson）</b>，而 Jackson 2 的
 * {@code com.fasterxml.jackson.databind.JsonNode} 在容器里既没有对应 bean、也不在
 * 默认序列化器覆盖范围内。使用普通 Map 可让本类与 Jackson 主版本解耦。
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

    /** 结构化负载（结构随 op 而定） */
    private Map<String, Object> data;

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

    /** data 下的原始字段 */
    public Object dataGet(String field) {
        return data == null ? null : data.get(field);
    }

    /** data 下的字符串字段 */
    public String dataString(String field) {
        Object value = dataGet(field);
        return value == null ? null : String.valueOf(value);
    }

    /** data 下的整型字段 */
    public Integer dataInt(String field) {
        Object value = dataGet(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** data 下的布尔字段 */
    public boolean dataBool(String field) {
        Object value = dataGet(field);
        return value instanceof Boolean flag && flag;
    }

    /** data 下的列表字段（元素为对象时以 Map 呈现） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> dataList(String field) {
        Object value = dataGet(field);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** data 下的字符串列表字段 */
    @SuppressWarnings("unchecked")
    public List<String> dataStringList(String field) {
        Object value = dataGet(field);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    /** data 下的字符串映射字段 */
    @SuppressWarnings("unchecked")
    public Map<String, String> dataStringMap(String field) {
        Object value = dataGet(field);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, String>) map;
        }
        return Map.of();
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
}
