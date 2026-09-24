package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 正则生成请求：场景 + 参数（字段定义由 GET /options 下发）。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class RegexGenerateBody {

    /** 生成场景，如 PHONE_CN / EMAIL / IPV4 等（见 options.scenarios） */
    @NotBlank(message = "生成场景不能为空")
    private String scenario;

    /** 按场景而定的参数（select/switch/number 一律以字符串回传） */
    @NotNull(message = "生成参数不能为空")
    private Map<String, String> params;

    /** 正则标志（imux 子集），默认无标志 */
    @Size(max = 10, message = "正则标志过长")
    private String flags;
}
