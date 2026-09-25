package com.serverpanel.tools.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 手机号段新增/编辑请求体。
 *
 * @param id       编辑时必填（雪花 ID 字符串序列化）
 * @param prefix   号段前缀（3-4 位数字）
 * @param operator 运营商
 * @param segType  1基础运营商 2虚拟运营商 3物联卡
 * @param note     备注
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record PhoneSegmentBody(
        Long id,
        @NotBlank(message = "号段前缀不能为空")
        @Pattern(regexp = "\\d{3,4}", message = "号段前缀应为 3-4 位数字")
        String prefix,
        @NotBlank(message = "运营商不能为空")
        @Size(max = 32, message = "运营商名称过长") String operator,
        @NotNull(message = "卡类型不能为空")
        @Min(value = 1, message = "卡类型不合法")
        @Max(value = 3, message = "卡类型不合法") Integer segType,
        @Size(max = 128, message = "备注过长") String note) {
}
