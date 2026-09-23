package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.entity.SysQuickNav;
import com.serverpanel.system.mapper.SysQuickNavMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 快捷导航配置 Service。
 */
@Service
@RequiredArgsConstructor
public class QuickNavService {

    private final SysQuickNavMapper quickNavMapper;

    public PageResult<SysQuickNav> page(PageQuery query, String keyword) {
        Page<SysQuickNav> page = quickNavMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysQuickNav>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(SysQuickNav::getDisplayName, keyword)
                    .or().like(SysQuickNav::getRemark, keyword))
                .orderByAsc(SysQuickNav::getSort)
                .orderByAsc(SysQuickNav::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    /** 启用的导航配置（工作台使用，按排序升序） */
    public java.util.List<SysQuickNav> listEnabled() {
        return quickNavMapper.selectList(
            new LambdaQueryWrapper<SysQuickNav>()
                .eq(SysQuickNav::getStatus, 1)
                .orderByAsc(SysQuickNav::getSort)
                .orderByAsc(SysQuickNav::getId));
    }

    public void create(SysQuickNav body) {
        body.setId(null);
        normalize(body);
        quickNavMapper.insert(body);
    }

    public void update(SysQuickNav body) {
        // 不能只回统一文案：缺 id 是调用方最容易踩的坑（前端未持主键时会提交一个
        // 没有 id 的 PUT），只给「请求参数错误」让人无从排查。
        if (body.getId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缺少主键 id，无法更新");
        }
        if (quickNavMapper.selectById(body.getId()) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        normalize(body);
        quickNavMapper.updateById(body);
    }

    public void delete(Long id) {
        if (quickNavMapper.selectById(id) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        quickNavMapper.deleteById(id);
    }

    /** 兜底默认值 */
    private void normalize(SysQuickNav body) {
        if (body.getPort() == null) {
            body.setPort(-1);
        }
        if (body.getPath() == null) {
            body.setPath("");
        }
        if (body.getIcon() == null || body.getIcon().isBlank()) {
            body.setIcon("lucide:app-window");
        }
        if (body.getSort() == null) {
            body.setSort(0);
        }
        if (body.getStatus() == null) {
            body.setStatus(1);
        }
    }
}
