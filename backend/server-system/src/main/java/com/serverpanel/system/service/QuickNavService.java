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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快捷导航配置 Service。
 */
@Service
@RequiredArgsConstructor
public class QuickNavService {

    private final SysQuickNavMapper quickNavMapper;

    /** 域名里误填的方案前缀：http://host 或 https://host:8443/xxx */
    private static final Pattern SCHEME_PREFIX =
        Pattern.compile("^(https?)://(.*)$", Pattern.CASE_INSENSITIVE);

    public PageResult<SysQuickNav> page(PageQuery query, String keyword) {
        Page<SysQuickNav> page = quickNavMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<SysQuickNav>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                    .like(SysQuickNav::getDisplayName, keyword)
                    .or().like(SysQuickNav::getDomain, keyword)
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
        normalizeDomain(body);
        body.setHttps(body.getHttps() != null && body.getHttps() == 1 ? 1 : 0);
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

    /**
     * 域名归一化：只留 host[:port]，协议交给 https 字段。
     *
     * <p>运维最常见的输入是直接粘一整条地址，所以遇到 http(s):// 前缀时把它
     * 翻译成 https 标记，而不是存成自相矛盾的「https=1 + http://xxx」；
     * 路径部分丢弃 —— 表里另有 path 列，域名混着路径会拼出双份前缀。
     */
    private void normalizeDomain(SysQuickNav body) {
        String raw = body.getDomain();
        if (raw == null || raw.isBlank()) {
            body.setDomain("");
            return;
        }
        String domain = raw.trim();
        Matcher matcher = SCHEME_PREFIX.matcher(domain);
        if (matcher.find()) {
            body.setHttps("https".equalsIgnoreCase(matcher.group(1)) ? 1 : 0);
            domain = matcher.group(2);
        }
        int slash = domain.indexOf('/');
        if (slash >= 0) {
            domain = domain.substring(0, slash);
        }
        body.setDomain(domain.trim());
    }
}
