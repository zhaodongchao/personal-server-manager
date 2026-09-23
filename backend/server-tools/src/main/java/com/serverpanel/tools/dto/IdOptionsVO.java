package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * ID 生成器可选清单。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdOptionsVO {

    /** 全部方案（含位分配、参数定义、优缺点） */
    private List<IdSchemeVO> schemes;

    /** 大类：数据库原生自增类 / 随机与时间哈希类 / 雪花及其变种 */
    private List<OptionVO> groups;

    /** 单次生成数量上限 */
    private int maxCount;

    /** 单次返回「逐条位段拆解」的行数上限（超过则只返回 ID 本身，避免响应体过大） */
    private int segLimit;
}
