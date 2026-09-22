package com.serverpanel.appstack.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 执行器出参。
 *
 * <p>{@code id} 为字符串：19 位雪花 ID 超出 JS 安全整数范围，直接给 number 会精度丢失，
 * 回传时查不到记录（本项目实测过的高频缺陷）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ExecutorVO {

    private String id;

    private String appName;

    private String executorName;

    /** BUILTIN / HTTP */
    private String type;

    private String baseUrl;

    /** 是否已配置令牌 */
    private boolean tokenSet;

    /** 令牌掩码（固定 ******，绝不回显明文） */
    private String authTokenMasked;

    private String status;

    private Integer failStreak;

    private LocalDateTime lastBeatAt;

    private String lastError;

    private String remark;

    private LocalDateTime createdAt;
}
