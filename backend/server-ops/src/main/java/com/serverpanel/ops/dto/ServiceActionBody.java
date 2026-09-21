package com.serverpanel.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 服务操作请求体。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceActionBody {

    /** start / stop / restart / reload / try-restart / enable / disable / mask / unmask / reset-failed / kill */
    @NotBlank(message = "操作不能为空")
    private String action;

    /** enable/disable/mask/unmask 是否同时生效（--now） */
    private Boolean now;

    /** kill 使用的信号，默认 SIGTERM */
    private String signal;

    /** L3 二次确认：需原样键入单元名 */
    @Size(max = 128, message = "确认串过长")
    private String confirm;
}
