package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 任务启停请求体（列表内快速开关）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class CronStatusBody {

    /** 1 启用 0 停用 */
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Integer status;
}
