package com.serverpanel.tools.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.BankBinBody;
import com.serverpanel.tools.entity.SysBankBin;
import com.serverpanel.tools.mapper.SysBankBinMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 银行卡 BIN 管理服务（CRUD，重名校验 + 唯一键双兜底）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Service
@RequiredArgsConstructor
public class BankBinService {

    private final SysBankBinMapper binMapper;

    /**
     * 分页（bankName 筛选、bin/名称检索）。
     */
    public PageResult<SysBankBin> page(PageQuery query, String bankName, String keyword) {
        Page<SysBankBin> page = binMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysBankBin>()
                        .eq(bankName != null && !bankName.isBlank(), SysBankBin::getBankName, bankName)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysBankBin::getBin, keyword)
                                .or().like(SysBankBin::getBankName, keyword))
                        .orderByAsc(SysBankBin::getBin));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /** 新增（bin 重复拒绝） */
    public void create(BankBinBody body) {
        checkDuplicate(body.bin(), null);
        SysBankBin entity = new SysBankBin();
        applyBody(entity, body);
        entity.setId(null);
        binMapper.insert(entity);
    }

    /** 编辑（bin 重复拒绝，排除自身） */
    public void update(BankBinBody body) {
        if (body.id() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        SysBankBin db = binMapper.selectById(body.id());
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        checkDuplicate(body.bin(), body.id());
        applyBody(db, body);
        binMapper.updateById(db);
    }

    /** 删除（不存在报 404） */
    public void delete(Long id) {
        if (binMapper.selectById(id) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        binMapper.deleteById(id);
    }

    private void checkDuplicate(String bin, Long excludeId) {
        Long count = binMapper.selectCount(new LambdaQueryWrapper<SysBankBin>()
                .eq(SysBankBin::getBin, bin)
                .ne(excludeId != null, SysBankBin::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException(ErrorCode.TOOLS_BD_BIN_DUPLICATE);
        }
    }

    private void applyBody(SysBankBin entity, BankBinBody body) {
        entity.setBin(body.bin());
        entity.setBankName(body.bankName());
        entity.setBankShort(body.bankShort());
        entity.setCardType(body.cardType());
        entity.setCardLen(body.cardLen());
        entity.setNote(body.note());
    }
}
