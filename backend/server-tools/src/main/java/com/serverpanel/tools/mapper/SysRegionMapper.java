package com.serverpanel.tools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.serverpanel.tools.entity.SysRegion;

/**
 * 行政区划 Mapper（批量 UPSERT 见 XML）。
 *
 * <p>启动类 {@code @MapperScan("com.serverpanel.**.mapper")} 已覆盖本包，无需额外注解。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public interface SysRegionMapper extends BaseMapper<SysRegion> {

    /**
     * 批量 UPSERT：存在（按 code 唯一键）则更新名称/层级/父级/状态，否则插入。
     *
     * <p>不动 short_name：外部源没有简称，覆写会把历史维护清掉。
     *
     * @param list 本批次行（调用方按 500 行分批）
     * @return 影响行数（UPSERT 语义下一行计 2，仅作非零判断用）
     */
    int batchUpsert(java.util.List<SysRegion> list);
}
