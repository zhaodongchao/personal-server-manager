package com.serverpanel.tools.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.tools.dto.RegionNodeVO;
import com.serverpanel.tools.entity.SysRegion;
import com.serverpanel.tools.mapper.SysRegionMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 行政区划查询服务（管理页）。
 *
 * <p>树为懒加载：按 parentCode 逐级取子节点，避免 4.5 万行一次性下发；
 * 检索走分页表格视图（keyword 命中 code 或 name）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Service
@RequiredArgsConstructor
public class RegionService {

    private final SysRegionMapper regionMapper;

    /**
     * 懒加载子节点（parentCode 为空返回省级）。
     */
    public List<RegionNodeVO> tree(String parentCode) {
        LambdaQueryWrapper<SysRegion> wrapper = new LambdaQueryWrapper<>();
        if (parentCode == null || parentCode.isBlank()) {
            wrapper.eq(SysRegion::getLevel, 1);
        } else {
            wrapper.eq(SysRegion::getParentCode, parentCode);
        }
        wrapper.orderByAsc(SysRegion::getCode);
        List<SysRegion> rows = regionMapper.selectList(wrapper);
        return rows.stream()
                .map(row -> new RegionNodeVO(row.getId(), row.getCode(), row.getName(),
                        row.getLevel(), row.getStatus(), row.getLevel() >= 4, null))
                .toList();
    }

    /**
     * 分页检索（keyword 命中 code 或 name，可按 level/status 筛选）。
     */
    public PageResult<SysRegion> page(PageQuery query, String keyword, Integer level, Integer status) {
        Page<SysRegion> page = regionMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysRegion>()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysRegion::getCode, keyword)
                                .or().like(SysRegion::getName, keyword))
                        .eq(level != null, SysRegion::getLevel, level)
                        .eq(status != null, SysRegion::getStatus, status)
                        .orderByAsc(SysRegion::getCode));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /** 当前行数（status 筛选；null=全部） */
    public long count(Integer status) {
        return regionMapper.selectCount(new LambdaQueryWrapper<SysRegion>()
                .eq(status != null, SysRegion::getStatus, status));
    }

    /** 是否已同步（表内有数据即视为已同步） */
    public boolean ready() {
        return regionMapper.selectCount(null) > 0;
    }
}
