package com.serverpanel.appstack.dto;

import java.util.Map;

import lombok.Data;

/**
 * 任务动作类入参（删除 / 停止 / 立即执行）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class JobActionBody {

    /** 危险操作的确认串（后端会告知应填什么） */
    private String confirm;

    /** 立即执行时的临时参数覆盖 */
    private Map<String, Object> paramOverride;
}
