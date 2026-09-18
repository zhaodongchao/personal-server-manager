package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 计划任务创建/更新请求体。
 */
@Data
public class CronJobBody {

    private Long id;

    @NotBlank(message = "任务名不能为空")
    @Size(max = 60, message = "任务名过长")
    private String name;

    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 60, message = "cron 表达式过长")
    private String cronExpr;

    @NotBlank(message = "命令不能为空")
    @Size(max = 500, message = "命令过长")
    private String command;

    @Min(value = 1, message = "超时时间最小 1 秒")
    @Max(value = 86400, message = "超时时间最大 86400 秒")
    private Integer timeoutSec;

    /** 1 启用 0 停用 */
    @Min(0)
    @Max(1)
    private Integer status;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
