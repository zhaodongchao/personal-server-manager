package com.serverpanel.common.jackson;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 容错整型反序列化器（Jackson 3.x）。允许调用方以布尔、数字、字符串等多种形式传入
 * 本应映射为 Integer 的字段（典型如 status 的 0/1 与 true/false）。
 *
 * <p>映射规则：
 * <ul>
 *   <li>null / 缺失 -> {@code null}</li>
 *   <li>布尔 true -> {@code 1}，false -> {@code 0}</li>
 *   <li>数字 -> {@code intValue()}</li>
 *   <li>字符串 "true"/"false"（忽略大小写）-> 1/0；可解析数字 -> 对应整数</li>
 * </ul>
 *
 * <p>用于消除「布尔写入 Integer 字段导致 HttpMessageNotReadable -> 400」这一类
 * 接口参数错误。仅作用于入站 JSON 反序列化，不影响 MyBatis 落库与出站序列化。
 *
 * @author zhaodc
 * @since 2026-09-23 17:00 UTC+8
 */
public class FlexIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken t = p.currentToken();
        if (t == null || t == JsonToken.VALUE_NULL) {
            return null;
        }
        if (t == JsonToken.VALUE_TRUE) {
            return 1;
        }
        if (t == JsonToken.VALUE_FALSE) {
            return 0;
        }
        if (t.isNumeric()) {
            return p.getIntValue();
        }
        if (t == JsonToken.VALUE_STRING) {
            String text = p.getValueAsString();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            String s = text.trim();
            if ("true".equalsIgnoreCase(s)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(s)) {
                return 0;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 无法识别的字符串交由 @Valid / 业务校验拦截
                return null;
            }
        }
        // 非标量（对象 / 数组等非法标量）交由 Jackson 默认强转，保持原 400 语义
        return p.getIntValue();
    }
}
