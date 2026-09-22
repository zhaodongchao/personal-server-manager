package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * cron 校验入参。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class CronValidateBody {

    @NotBlank(message = "cron 表达式不能为空")
    private String cronExpr;
}
