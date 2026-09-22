package com.serverpanel.appstack.dto;

import com.serverpanel.common.core.PageQuery;

import lombok.Getter;
import lombok.Setter;

/**
 * 任务分页查询参数。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Getter
@Setter
public class JobQuery extends PageQuery {

    /** 任务名 / 描述模糊匹配 */
    private String keyword;

    private String handler;

    private Long executorId;

    /** 0 停用 / 1 启用 */
    private Integer status;

    /** 上次执行结果 */
    private String lastStatus;
}
