package com.serverpanel.appstack.job;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 定时任务模块的纯静态工具：JSON 读写、参数取值、输出截断、模板变量替换、令牌掩码。
 *
 * <p><b>JSON 为什么自带 mapper</b>：本项目序列化栈是 <b>Jackson 3（tools.jackson）</b>，
 * MyBatis-Plus 自带的 typeHandler 走的是 Jackson 2，不能用于 {@code handler_param}
 * 这类 JSON 字段。这里统一用 Jackson 3 的 {@link JsonMapper}，与本模块的
 * {@code handler_param} 采用「实体持有 String + 工具类解析」的组合。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public final class JobSupport {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 允许在参数里引用的模板变量白名单（不做通用表达式求值，防「参数即代码」） */
    public static final Set<String> ALLOWED_TEMPLATE_VARS =
            Set.of("MYSQL_PWD", "JOB_ID", "JOB_NAME", "LOG_ID");

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    private JobSupport() {}

    // ==================== JSON ====================

    /** 对象转 JSON；入参为空返回 null */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "任务参数无法序列化：" + e.getMessage());
        }
    }

    /** JSON 转 Map；空串返回空 Map，非法 JSON 抛 6033 */
    public static Map<String, Object> toMap(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        Object parsed;
        try {
            parsed = JSON.readValue(json, Object.class);
        } catch (RuntimeException e) {
            throw new ServiceException(ErrorCode.JOB_HANDLER_INVALID.getCode(),
                    "任务参数不是合法 JSON：" + e.getMessage());
        }
        if (parsed instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    // ==================== 参数取值 ====================

    /** 取字符串；空白返回 null */
    public static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** 取整数 */
    public static int intOf(Map<String, Object> map, String key, int defaultValue) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 取字符串列表 */
    public static List<String> strList(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            // 兼容前端以「一行一个」的换行写法提交参数
            List<String> result = new ArrayList<>();
            for (String line : text.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    result.add(line.trim());
                }
            }
            return result;
        }
        return List.of();
    }

    /** 取字符串映射（值可为 Map 或 JSON 对象串） */
    public static Map<String, String> strMap(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(String.valueOf(k), String.valueOf(v));
                }
            });
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            toMap(text).forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
        }
        return result;
    }

    // ==================== 文本 ====================

    /**
     * 按 UTF-8 字节上限截断，且不切断多字节字符。
     *
     * <p>输出入库存 TEXT 列；不设上限的话，一条 {@code mysqldump} 的正常输出就能把日志表撑爆。
     */
    public static String truncate(String text, int maxBytes) {
        if (text == null) {
            return null;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        int budget = Math.max(maxBytes - 64, 0);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String piece = new String(Character.toChars(codePoint));
            int size = piece.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > budget) {
                break;
            }
            sb.append(piece);
            used += size;
            index += Character.charCount(codePoint);
        }
        sb.append("\n...[输出超过 ").append(maxBytes).append(" 字节，已截断]");
        return sb.toString();
    }

    /** 令牌掩码：非空即返回固定掩码，绝不回显明文 */
    public static String maskToken(String token) {
        return token == null || token.isBlank() ? null : "******";
    }

    /**
     * 模板变量替换：只替换 {@link #ALLOWED_TEMPLATE_VARS} 里的名字，其余原样保留。
     *
     * <p>刻意不做通用表达式求值 —— 一旦允许「参数里写表达式」，白名单命令就退化成了
     * 任意代码执行（设计 §7.2(1) 的明确否决项）。
     */
    public static String applyVars(String text, Map<String, String> vars) {
        if (text == null || text.isEmpty() || vars.isEmpty()) {
            return text;
        }
        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = ALLOWED_TEMPLATE_VARS.contains(name) ? vars.get(name) : null;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 受保护服务的确认关键字（与服务器配置模块的 L3 关键字风格一致） */
    public static String confirmKeywordFor(String unit) {
        return "APPLY " + unit;
    }
}
