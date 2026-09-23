package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.entity.SysDictData;
import com.serverpanel.system.entity.SysDictType;
import com.serverpanel.system.mapper.SysDictDataMapper;
import com.serverpanel.system.mapper.SysDictTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 字典管理 Service（类型 + 数据）。
 */
@Service
@RequiredArgsConstructor
public class SysDictService {

    private final SysDictTypeMapper typeMapper;
    private final SysDictDataMapper dataMapper;

    // ===== 字典类型 =====

    public PageResult<SysDictType> typePage(PageQuery query, String keyword) {
        Page<SysDictType> page = typeMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysDictType>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(SysDictType::getDictName, keyword)
                    .or().like(SysDictType::getDictType, keyword))
                .orderByAsc(SysDictType::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    @Transactional
    public void typeCreate(SysDictType body) {
        checkTypeUnique(null, body.getDictType());
        body.setId(null);
        typeMapper.insert(body);
    }

    @Transactional
    public void typeUpdate(SysDictType body) {
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        checkTypeUnique(body.getId(), body.getDictType());
        typeMapper.updateById(body);
    }

    @Transactional
    public void typeDelete(Long id) {
        SysDictType type = typeMapper.selectById(id);
        if (type == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        if (dataMapper.selectCount(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, type.getDictType())) > 0) {
            throw new ServiceException(ErrorCode.DICT_DATA_EXISTS);
        }
        typeMapper.deleteById(id);
    }

    // ===== 字典数据 =====

    public PageResult<SysDictData> dataPage(PageQuery query, String dictType) {
        Page<SysDictData> page = dataMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysDictData>()
                .eq(dictType != null && !dictType.isBlank(),
                    SysDictData::getDictType, dictType)
                .orderByAsc(SysDictData::getSort));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 按类型取启用字典（前端下拉） */
    public List<SysDictData> dataByType(String dictType) {
        return dataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
            .eq(SysDictData::getDictType, dictType)
            .eq(SysDictData::getStatus, 1)
            .orderByAsc(SysDictData::getSort));
    }

    @Transactional
    public void dataCreate(SysDictData body) {
        body.setId(null);
        dataMapper.insert(body);
    }

    @Transactional
    public void dataUpdate(SysDictData body) {
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        dataMapper.updateById(body);
    }

    @Transactional
    public void dataDelete(Long id) {
        dataMapper.deleteById(id);
    }

    private void checkTypeUnique(Long excludeId, String dictType) {
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<SysDictType>()
            .eq(SysDictType::getDictType, dictType)
            .ne(excludeId != null, SysDictType::getId, excludeId);
        if (typeMapper.selectCount(wrapper) > 0) {
            throw new ServiceException(ErrorCode.DICT_TYPE_EXISTS);
        }
    }
}
