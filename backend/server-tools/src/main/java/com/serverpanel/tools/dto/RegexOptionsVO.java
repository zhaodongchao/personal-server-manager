package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 正则工具 GET /options 出参：场景清单、标志清单与各项上限。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexOptionsVO {

    /** 可选生成场景（schema 驱动字段） */
    private List<RegexScenarioVO> scenarios;

    /** 可选标志 */
    private List<RegexFlagVO> flags;

    /** 各项上限 */
    private RegexLimitsVO limits;
}
