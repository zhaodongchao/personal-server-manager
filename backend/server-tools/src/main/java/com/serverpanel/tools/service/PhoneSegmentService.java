package com.serverpanel.tools.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.PhoneSegmentBody;
import com.serverpanel.tools.entity.SysPhoneSegment;
import com.serverpanel.tools.mapper.SysPhoneSegmentMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 手机号段管理服务（CRUD，重名由业务校验 + 唯一键双兜底）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Service
@RequiredArgsConstructor
public class PhoneSegmentService {

    private final SysPhoneSegmentMapper segmentMapper;

    /**
     * 分页（operator 筛选）。
     */
    public PageResult<SysPhoneSegment> page(PageQuery query, String operator, String keyword) {
        Page<SysPhoneSegment> page = segmentMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysPhoneSegment>()
                        .eq(operator != null && !operator.isBlank(), SysPhoneSegment::getOperator, operator)
                        .like(keyword != null && !keyword.isBlank(), SysPhoneSegment::getPrefix, keyword)
                        .orderByAsc(SysPhoneSegment::getPrefix));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /** 新增（prefix 重复拒绝） */
    public void create(PhoneSegmentBody body) {
        checkDuplicate(body.prefix(), null);
        SysPhoneSegment entity = new SysPhoneSegment();
        applyBody(entity, body);
        entity.setId(null);
        segmentMapper.insert(entity);
    }

    /** 编辑（prefix 重复拒绝，排除自身） */
    public void update(PhoneSegmentBody body) {
        if (body.id() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        SysPhoneSegment db = segmentMapper.selectById(body.id());
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        checkDuplicate(body.prefix(), body.id());
        applyBody(db, body);
        segmentMapper.updateById(db);
    }

    /** 删除（不存在报 404） */
    public void delete(Long id) {
        if (segmentMapper.selectById(id) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        segmentMapper.deleteById(id);
    }

    private void checkDuplicate(String prefix, Long excludeId) {
        Long count = segmentMapper.selectCount(new LambdaQueryWrapper<SysPhoneSegment>()
                .eq(SysPhoneSegment::getPrefix, prefix)
                .ne(excludeId != null, SysPhoneSegment::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SEGMENT_DUPLICATE);
        }
    }

    private void applyBody(SysPhoneSegment entity, PhoneSegmentBody body) {
        entity.setPrefix(body.prefix());
        entity.setOperator(body.operator());
        entity.setSegType(body.segType());
        entity.setNote(body.note());
    }
}
