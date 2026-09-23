package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * ID 生成请求体。
 *
 * <p>各方案的参数差异极大（雪花要机器号、序列要步长与缓存段、UUIDv1 要 node），
 * 故用 {@code params} 承载，字段定义由 {@code GET /options} 下发，
 * 与混淆接口、内置任务的 {@code execute(Map)} 保持同一风格。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdGenerateBody {

    /** 方案编码：MYSQL_AUTO_INCREMENT / SEQUENCE / UUID_V1 / UUID_V4 / UUID_V7 / OBJECT_ID / SNOWFLAKE / UID_GENERATOR / SONYFLAKE */
    @NotBlank(message = "生成方案不能为空")
    private String scheme;

    /** 生成数量，1 ~ maxCount（默认 10） */
    private Integer count = 10;

    /** 按方案而定的参数 */
    private Map<String, String> params;
}
