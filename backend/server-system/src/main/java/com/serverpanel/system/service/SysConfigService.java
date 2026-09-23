package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.constant.CommonConstants;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.entity.SysConfig;
import com.serverpanel.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 参数配置 Service。
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper configMapper;

    public PageResult<SysConfig> page(PageQuery query, String keyword) {
        Page<SysConfig> page = configMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysConfig>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(SysConfig::getConfigName, keyword)
                    .or().like(SysConfig::getConfigKey, keyword))
                .orderByAsc(SysConfig::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 按 key 取值（内部模块使用；不存在返回默认值） */
    public String getValue(String key, String defaultValue) {
        SysConfig config = configMapper.selectOne(
            new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        return config != null ? config.getConfigValue() : defaultValue;
    }

    public void update(SysConfig body) {
        // 缺主键时不能只回统一文案，否则调用方（含前端表单未持有主键的场景）
        // 拿到一个「请求参数错误」完全无从判断缺的是 id
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        SysConfig db = configMapper.selectById(body.getId());
        if (db == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        body.setConfigKey(db.getConfigKey());
        configMapper.updateById(body);
    }

    public void create(SysConfig body) {
        if (configMapper.selectCount(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, body.getConfigKey())) > 0) {
            throw new ServiceException(ErrorCode.CONFIG_KEY_EXISTS);
        }
        body.setId(null);
        if (body.getConfigType() == null || body.getConfigType().isBlank()) {
            body.setConfigType("N");
        }
        configMapper.insert(body);
    }

    public void delete(Long id) {
        SysConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        if (CommonConstants.CONFIG_TYPE_BUILTIN.equals(config.getConfigType())) {
            throw new ServiceException(ErrorCode.BUILTIN_DATA);
        }
        configMapper.deleteById(id);
    }
}
