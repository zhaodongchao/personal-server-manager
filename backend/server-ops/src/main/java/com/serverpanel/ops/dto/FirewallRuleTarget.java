package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 防火墙规则的目标端口。
 *
 * <p>四种形态对应 ufw 的四种写法，前端按类型切换输入控件，后端按类型编译 argv：
 * <ul>
 *   <li>{@code port} —— 单个端口，如 8080</li>
 *   <li>{@code range} —— 端口范围，如 40000-40100</li>
 *   <li>{@code multi} —— 多端口，如 [80, 443]</li>
 *   <li>{@code any} —— 任意端口（配合来源限制使用）</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallRuleTarget {

    /** port / range / multi / any */
    private String kind;

    @Min(value = 1, message = "端口范围 1-65535")
    @Max(value = 65535, message = "端口范围 1-65535")
    private Integer port;

    @Min(value = 1, message = "端口范围 1-65535")
    @Max(value = 65535, message = "端口范围 1-65535")
    private Integer portEnd;

    @Size(max = 15, message = "多端口最多 15 个")
    private List<Integer> ports;
}
