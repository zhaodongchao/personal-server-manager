package com.serverpanel.appstack.dto;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import lombok.Data;

/**
 * 日志清理入参。
 *
 * <p>至少要给出一个过滤条件 —— 面板不提供「无条件清空全表」的入口。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ClearLogBody {

    private Long jobId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beforeTime;

    private String status;

    /** 需填 {@code CLEAR LOG} */
    private String confirm;
}
