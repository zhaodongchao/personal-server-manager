package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 执行器登记/编辑入参。
 *
 * <p>令牌 {@code authToken} 是**只写**字段：出参一律掩码，不接受回读。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ExecutorBody {

    @NotBlank(message = "执行器 AppName 不能为空")
    @Size(max = 64, message = "AppName 最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_\\-]+$", message = "AppName 只允许字母、数字、下划线、短横线")
    private String appName;

    @Size(max = 64)
    private String executorName;

    /** 只允许 HTTP（内置执行器由系统 seed，不接受登记） */
    @NotBlank(message = "执行器类型不能为空")
    private String type;

    @Size(max = 255)
    private String baseUrl;

    @Size(max = 255)
    private String authToken;

    /** AVAILABLE / DISABLED（UNREACHABLE 由系统按失败次数自动置位） */
    private String status;

    @Size(max = 200)
    private String remark;
}
