package com.serverpanel.tools.service;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.tools.entity.SysRegion;
import com.serverpanel.tools.mapper.SysRegionMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 区划查询服务（身份证 / 统一社会信用代码解析共用）。
 *
 * <p>查询语义：
 * <ul>
 *   <li>表为空 = 区划数据未同步 → {@link Result#notReady()}，解析降级；</li>
 *   <li>精确命中 6 位区县 → 沿 parent_code 上溯拼出「省-市-区县」全称；</li>
 *   <li>未命中 6 位（老代码已撤销或未收录）→ 依次回退 4 位市级 / 2 位省级，
 *       partial=true 提示只到上级；</li>
 *   <li>完全未命中 → {@link Result#miss()}。</li>
 * </ul>
 *
 * <p>查询走 uk_code 索引，单次解析最多 4 次点查，无需缓存。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Service
@RequiredArgsConstructor
public class RegionLookupService {

    private final SysRegionMapper regionMapper;

    /** 就绪状态枚举：ready 已同步 / notReady 未同步 / miss 已同步但代码未收录 */
    public enum Status { READY, NOT_READY, MISS }

    /**
     * 区划解析结果。
     *
     * @param status 就绪状态
     * @param full   区划全称链（如「北京市 市辖区 朝阳区」，未命中时为空）
     * @param partial true=未精确命中 6 位，仅识别到上级
     */
    public record Result(Status status, String full, boolean partial) {

        public static Result notReady() {
            return new Result(Status.NOT_READY, "", false);
        }

        public static Result miss() {
            return new Result(Status.MISS, "", false);
        }
    }

    /**
     * 是否已同步（options 的 regionReady 用；避免每次解析都 count，这里现查一次即可）。
     */
    public boolean ready() {
        return regionMapper.selectCount(null) > 0;
    }

    /**
     * 解析 6 位区划代码。
     *
     * @param code 区划代码（6 位；长度不足按前缀逐级回退）
     * @return 解析结果
     */
    public Result resolve6(String code) {
        if (code == null || code.length() < 2) {
            return Result.miss();
        }
        if (!ready()) {
            return Result.notReady();
        }
        // 精确命中（任意层级均可：省级机构代码可能只有 2 位有效）
        String target = code.length() > 6 ? code.substring(0, 6) : code;
        SysRegion hit = findByCode(target);
        boolean partial = false;
        if (hit == null) {
            // 依次回退 4 位市级、2 位省级
            if (target.length() >= 4) {
                hit = findByCode(target.substring(0, 4));
                partial = hit != null;
            }
            if (hit == null) {
                hit = findByCode(target.substring(0, 2));
                partial = hit != null;
            }
            if (hit == null) {
                return Result.miss();
            }
        }
        return new Result(Status.READY, chainOf(hit), partial);
    }

    /** 按 code 精确查（含停用行：老身份证历史区划仍可解析） */
    private SysRegion findByCode(String code) {
        return regionMapper.selectOne(new LambdaQueryWrapper<SysRegion>()
                .eq(SysRegion::getCode, code).last("LIMIT 1"));
    }

    /** 沿 parent_code 上溯拼全称链（最远到省级，3 跳封顶防环） */
    private String chainOf(SysRegion region) {
        List<String> names = new ArrayList<>();
        SysRegion current = region;
        for (int hop = 0; current != null && hop < 3; hop++) {
            names.add(0, current.getName());
            String parent = current.getParentCode();
            current = (parent == null || parent.isBlank()) ? null : findByCode(parent);
        }
        return String.join(" ", names);
    }
}
