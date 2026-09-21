package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * cron 表达式校验 / 预览请求体。
 *
 * <p>表单里每改一次表达式就调一次：既做校验（错误原因精确到段位），
 * 也顺带回未来 N 次执行时间与人话描述。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class CronPreviewBody {

    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 60, message = "cron 表达式过长")
    private String cronExpr;

    /** 预览次数，默认 5，最多 20 */
    @Min(value = 1, message = "预览次数最小 1")
    @Max(value = 20, message = "预览次数最多 20")
    private Integer count;
}
