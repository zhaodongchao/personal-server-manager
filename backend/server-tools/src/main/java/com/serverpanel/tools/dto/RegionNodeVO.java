package com.serverpanel.tools.dto;

import java.util.List;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 行政区划树节点（懒加载：展开时按 parentCode 取下一级）。
 *
 * @param code        区划代码
 * @param name        名称
 * @param level       1省 2市 3区县 4乡镇街道
 * @param status      1启用 0停用（软删）
 * @param leaf        是否叶子（乡镇级）
 * @param children    子节点（懒加载模式下为 null）
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record RegionNodeVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String code,
        String name,
        Integer level,
        Integer status,
        boolean leaf,
        List<RegionNodeVO> children) {
}
