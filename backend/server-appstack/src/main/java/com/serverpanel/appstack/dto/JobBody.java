package com.serverpanel.appstack.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 新增/编辑任务的入参。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class JobBody {

    @NotBlank(message = "任务名不能为空")
    @Size(max = 64, message = "任务名最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+$",
            message = "任务名只允许字母、数字、下划线、短横线、点与冒号")
    private String jobName;

    @Size(max = 200, message = "描述最长 200 字符")
    private String jobDesc;

    @NotNull(message = "执行器不能为空")
    private Long executorId;

    @NotBlank(message = "处理器不能为空")
    private String handler;

    /** 处理器参数（按 handler 的 schema 组织；不填则按空参数处理） */
    private Map<String, Object> handlerParam;

    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 64, message = "cron 表达式最长 64 字符")
    private String cronExpr;

    private String routeStrategy;

    private String blockStrategy;

    private Integer timeoutSec;

    private Integer retryCount;

    /** 0 停用 / 1 启用 */
    private Integer status;

    /** 命中服务保护清单的破坏性服务动作需填 {@code APPLY <unit>} */
    @Size(max = 32, message = "确认关键字最长 32 字符")
    private String confirmKeyword;
}
