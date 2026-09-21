package com.serverpanel.ops.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批量服务操作请求体。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceBatchBody {

    @NotEmpty(message = "请至少选择一个服务")
    @Size(max = 50, message = "单次最多操作 50 个服务")
    private List<String> names;

    @NotBlank(message = "操作不能为空")
    private String action;

    private Boolean now;

    /** 批量高危操作的二次确认串（需键入 CONFIRM） */
    private String confirm;
}
