package com.serverpanel.tools.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 银行卡 BIN 新增/编辑请求体。
 *
 * @param id        编辑时必填（雪花 ID 字符串序列化）
 * @param bin       BIN 前缀（6-10 位数字）
 * @param bankName  发卡行全称
 * @param bankShort 简称
 * @param cardType  1借记卡 2贷记卡 3准贷记卡
 * @param cardLen   标准卡长（16/19）
 * @param note      备注
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record BankBinBody(
        Long id,
        @NotBlank(message = "BIN 不能为空")
        @Pattern(regexp = "\\d{6,10}", message = "BIN 应为 6-10 位数字")
        String bin,
        @NotBlank(message = "发卡行名称不能为空")
        @Size(max = 64, message = "发卡行名称过长") String bankName,
        @Size(max = 32, message = "简称过长") String bankShort,
        @NotNull(message = "卡种不能为空")
        @Min(value = 1, message = "卡种不合法")
        @Max(value = 3, message = "卡种不合法") Integer cardType,
        @NotNull(message = "标准卡长不能为空")
        @Min(value = 12, message = "卡长不合法")
        @Max(value = 23, message = "卡长不合法") Integer cardLen,
        @Size(max = 128, message = "备注过长") String note) {
}
