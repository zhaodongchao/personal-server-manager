package com.serverpanel.tools.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.RegexTemplateBody;
import com.serverpanel.tools.dto.RegexTemplateQuery;
import com.serverpanel.tools.dto.RegexTemplateVO;
import com.serverpanel.tools.entity.RegexTemplate;
import com.serverpanel.tools.mapper.RegexTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则模板 Service：全局共享模板的分页 / 全量 / 增删改。
 *
 * <p>保存时做三道校验，保证库里的每条模板都能被 Java 引擎直接使用：
 * <ol>
 *   <li>flags 白名单（复用 {@link RegexService#parseFlags}，imux 且不重复）；</li>
 *   <li>pattern 必须能通过 {@link Pattern#compile}（存一条编译不过的正则没有意义）；</li>
 *   <li>名称全局唯一（先查后插 + 唯一键 DuplicateKeyException 兜底，双保险）。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Service
@RequiredArgsConstructor
public class RegexTemplateService {

    private final RegexTemplateMapper regexTemplateMapper;

    /** 与表列 VARCHAR(2000) 对齐的正则长度上限（Bean Validation 已挡一层，这里兜底常量） */
    public static final int MAX_PATTERN_LENGTH = 2000;

    /** 全量列表上限：模板是给下拉联动的，超过这个量就该靠搜索而不是翻页 */
    public static final int MAX_TEMPLATE_COUNT = 500;

    /** 分页查询（keyword 模糊名称/说明，category 精确筛选，sort 升序小在前） */
    public PageResult<RegexTemplateVO> page(RegexTemplateQuery query) {
        String keyword = blankToNull(query.getKeyword());
        String category = blankToNull(query.getCategory());
        Page<RegexTemplate> page = regexTemplateMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<RegexTemplate>()
                .and(keyword != null, w -> w
                    .like(RegexTemplate::getName, keyword)
                    .or().like(RegexTemplate::getDescription, keyword))
                .eq(category != null, RegexTemplate::getCategory, category)
                .orderByAsc(RegexTemplate::getSort)
                .orderByAsc(RegexTemplate::getName));
        List<RegexTemplateVO> records = page.getRecords().stream().map(RegexTemplateService::toVO).toList();
        return PageResult.of(records, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /** 全量列表（测试页下拉联动用，按 sort、name 排序） */
    public List<RegexTemplateVO> list() {
        Long total = regexTemplateMapper.selectCount(null);
        if (total != null && total > MAX_TEMPLATE_COUNT) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_INVALID,
                "模板数量已超过 " + MAX_TEMPLATE_COUNT + " 条，请先清理不再使用的模板");
        }
        return regexTemplateMapper.selectList(new LambdaQueryWrapper<RegexTemplate>()
                .orderByAsc(RegexTemplate::getSort)
                .orderByAsc(RegexTemplate::getName))
            .stream().map(RegexTemplateService::toVO).toList();
    }

    /** 新增模板 */
    public void create(RegexTemplateBody body) {
        validate(body);
        requireNameFree(body.getName(), null);
        RegexTemplate entity = new RegexTemplate();
        applyBody(entity, body);
        try {
            regexTemplateMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_NAME_DUPLICATED);
        }
    }

    /** 编辑模板（id 走路径变量；改名时排除自身查重） */
    public void update(Long id, RegexTemplateBody body) {
        RegexTemplate exists = regexTemplateMapper.selectById(id);
        if (exists == null) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_NOT_FOUND);
        }
        validate(body);
        requireNameFree(body.getName(), id);
        applyBody(exists, body);
        try {
            regexTemplateMapper.updateById(exists);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_NAME_DUPLICATED);
        }
    }

    /** 删除模板 */
    public void delete(Long id) {
        if (regexTemplateMapper.selectById(id) == null) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_NOT_FOUND);
        }
        regexTemplateMapper.deleteById(id);
    }

    // ========== 内部方法 ==========

    /** 保存前的语义校验：flags 白名单 + pattern 必须可编译（长度已由 Bean Validation 挡过） */
    private void validate(RegexTemplateBody body) {
        RegexService.parseFlags(body.getFlags() == null ? "" : body.getFlags());
        try {
            Pattern.compile(body.getPattern());
        } catch (PatternSyntaxException e) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_INVALID,
                "正则语法不合法：" + e.getDescription() + "（位置 " + e.getIndex() + "）");
        }
    }

    /** 名称唯一性校验（excludeId 非空表示编辑时排除自身） */
    private void requireNameFree(String name, Long excludeId) {
        Long count = regexTemplateMapper.selectCount(new LambdaQueryWrapper<RegexTemplate>()
            .eq(RegexTemplate::getName, name)
            .ne(excludeId != null, RegexTemplate::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEMPLATE_NAME_DUPLICATED);
        }
    }

    /** Body -> Entity 字段搬运 + 兜底默认值 */
    private void applyBody(RegexTemplate entity, RegexTemplateBody body) {
        entity.setName(body.getName().trim());
        entity.setPattern(body.getPattern());
        entity.setFlags(body.getFlags() == null ? "" : body.getFlags());
        entity.setCategory(body.getCategory() == null || body.getCategory().isBlank()
            ? "通用" : body.getCategory().trim());
        entity.setDescription(body.getDescription());
        entity.setSample(body.getSample());
        entity.setSort(body.getSort() == null ? 0 : body.getSort());
    }

    private static RegexTemplateVO toVO(RegexTemplate entity) {
        return new RegexTemplateVO(
            String.valueOf(entity.getId()), entity.getName(), entity.getPattern(),
            entity.getFlags(), entity.getCategory(), entity.getDescription(),
            entity.getSample(), entity.getSort(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
