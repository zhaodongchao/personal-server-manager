package com.serverpanel.appstack.service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.dto.IdSourceBody;
import com.serverpanel.appstack.dto.IdSourceVO;
import com.serverpanel.appstack.entity.AppIdSource;
import com.serverpanel.appstack.mapper.AppIdSourceMapper;
import com.serverpanel.appstack.service.source.IdSourceProvider;
import com.serverpanel.appstack.service.source.JdbcSupport;
import com.serverpanel.appstack.service.source.MysqlIdSource;
import com.serverpanel.appstack.service.source.PostgresIdSource;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.id.DbKind;
import com.serverpanel.common.id.IdSourceBatch;
import com.serverpanel.common.id.IdSourceGateway;
import com.serverpanel.common.id.IdSourceOption;
import com.serverpanel.common.id.IdSourceProbe;
import com.serverpanel.common.id.IdSourceSpec;
import com.serverpanel.framework.crypto.SecretCipher;

import lombok.extern.slf4j.Slf4j;

/**
 * 取号数据源服务 —— 兼具「登记管理（CRUD）」与「ID 生成器取号通道（SPI 实现）」两个角色。
 *
 * <p>把它做成一个类而不是拆两个，是因为二者围绕同一张表、同一份连接规格，
 * 拆开只会让 {@code specOf} 这种转换在两边重复。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Slf4j
@Service
public class IdSourceService implements IdSourceGateway {

    /** 方案编码：MySQL 自增（与 server-tools 的 IdService 约定一致） */
    public static final String SCHEME_MYSQL_AUTO_INCREMENT = "MYSQL_AUTO_INCREMENT";

    /** 方案编码：序列（与 server-tools 的 IdService 约定一致） */
    public static final String SCHEME_SEQUENCE = "SEQUENCE";

    private final AppIdSourceMapper mapper;

    private final SecretCipher cipher;

    private final JdbcSupport jdbc;

    private final Map<DbKind, IdSourceProvider> providers;

    public IdSourceService(AppIdSourceMapper mapper, SecretCipher cipher, JdbcSupport jdbc,
                           List<IdSourceProvider> providerBeans) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.jdbc = jdbc;
        Map<DbKind, IdSourceProvider> map = new EnumMap<>(DbKind.class);
        for (IdSourceProvider provider : providerBeans) {
            map.put(provider.kind(), provider);
        }
        this.providers = Map.copyOf(map);
        log.info("取号原语已装载：{}", this.providers.keySet());
    }

    // ==========================================================================
    // 登记管理
    // ==========================================================================

    public PageResult<IdSourceVO> page(PageQuery query, String keyword) {
        Page<AppIdSource> page = mapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<AppIdSource>()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(AppIdSource::getName, keyword).or()
                                .like(AppIdSource::getDbName, keyword).or()
                                .like(AppIdSource::getHost, keyword))
                        .orderByDesc(AppIdSource::getId));
        List<IdSourceVO> list = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public Long create(IdSourceBody body) {
        requireCipher();
        checkBody(body);
        if (body.getPassword() == null || body.getPassword().isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID, "新增数据源必须填写口令");
        }
        AppIdSource entity = new AppIdSource();
        apply(entity, body);
        entity.setPasswordCipher(cipher.encrypt(body.getPassword()));
        mapper.insert(entity);
        log.info("新增取号数据源：id={} name={} {}:{}/{}", entity.getId(), entity.getName(),
                entity.getHost(), entity.getPort(), entity.getDbName());
        return entity.getId();
    }

    public void update(Long id, IdSourceBody body) {
        AppIdSource entity = require(id);
        checkBody(body);
        apply(entity, body);
        if (body.getPassword() != null && !body.getPassword().isBlank()) {
            requireCipher();
            entity.setPasswordCipher(cipher.encrypt(body.getPassword()));
        }
        mapper.updateById(entity);
    }

    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
        log.info("删除取号数据源：id={}", id);
    }

    // ==========================================================================
    // IdSourceGateway 实现
    // ==========================================================================

    @Override
    public List<IdSourceOption> options() {
        List<AppIdSource> list = mapper.selectList(new LambdaQueryWrapper<AppIdSource>()
                .eq(AppIdSource::getStatus, 1)
                .orderByAsc(AppIdSource::getName));
        List<IdSourceOption> result = new java.util.ArrayList<>(list.size());
        for (AppIdSource source : list) {
            DbKind kind = DbKind.of(source.getDbType());
            String typeLabel = kind == null ? source.getDbType() : kind.label();
            result.add(new IdSourceOption(
                    String.valueOf(source.getId()),
                    source.getName() + "（" + typeLabel + " · " + source.getDbName() + "）",
                    kind == null ? null : kind.name(),
                    targetOf(source, kind)));
        }
        return result;
    }

    @Override
    public IdSourceProbe probe(Long sourceId) {
        AppIdSource source = require(sourceId);
        return providerOf(source).probe(specOf(source));
    }

    @Override
    public String initialize(Long sourceId) {
        AppIdSource source = require(sourceId);
        return providerOf(source).initialize(specOf(source));
    }

    @Override
    public IdSourceBatch fetch(Long sourceId, String scheme, int count, Map<String, String> params) {
        AppIdSource source = require(sourceId);
        DbKind kind = DbKind.of(source.getDbType());
        if (kind == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_SCHEME_MISMATCH,
                    "数据源类型无法识别：" + source.getDbType());
        }
        // 方案与库类型的匹配是硬约束：PostgreSQL 没有 AUTO_INCREMENT，
        // MySQL 也没有独立序列对象，用错了只会得到一句底层语法错误
        if (SCHEME_SEQUENCE.equals(scheme) && kind != DbKind.POSTGRESQL) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_SCHEME_MISMATCH,
                    "「序列」方案需要 PostgreSQL 数据源，当前数据源是 " + kind.label()
                            + "；MySQL 侧请用「自增计数器」方案");
        }
        if (SCHEME_MYSQL_AUTO_INCREMENT.equals(scheme) && kind != DbKind.MYSQL) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_SCHEME_MISMATCH,
                    "「自增计数器」方案需要 MySQL 数据源，当前数据源是 " + kind.label()
                            + "；PostgreSQL 侧请用「序列」方案");
        }
        return providerOf(source).fetch(specOf(source), count, params);
    }

    /** 取号对象名（不下发口令的轻量查询） */
    public String targetOfSource(Long sourceId) {
        AppIdSource source = require(sourceId);
        return targetOf(source, DbKind.of(source.getDbType()));
    }

    // ==========================================================================
    // 内部
    // ==========================================================================

    private void checkBody(IdSourceBody body) {
        DbKind kind = DbKind.of(body.getDbType());
        if (kind == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_SCHEME_MISMATCH,
                    "不支持的数据库类型：" + body.getDbType() + "（仅支持 POSTGRESQL / MYSQL）");
        }
        // 库名在这里就拦下：越早拦住「把取号对象建到生产库」的尝试越好
        jdbc.checkDatabase(body.getDbName());
        // 取号对象名也在登记时校验：与使用时（IdSourceProvider）用同一把尺子。
        // 否则会出现「登记成功、取号必败」的 fail-late —— 记录躺在列表里却永远取不到号。
        if (kind == DbKind.POSTGRESQL) {
            checkOptionalIdentifier(body.getSequenceName(), "序列名");
        } else {
            checkOptionalIdentifier(body.getTableName(), "自增表名");
        }
    }

    /**
     * 校验可选的取号对象名。
     *
     * <p>为空表示沿用默认对象名（默认名本身自带 {@code psm_} 前缀）；非空则按「自动创建」
     * 的标准校验：标识符正则 + {@code psm_} 前缀，与使用时 {@code IdSourceProvider}
     * 内的校验口径完全一致，避免登记与使用两套标准。
     *
     * @param name  对象名，允许为 {@code null} 或空白
     * @param label 报错文案中的语义标签（如「序列名」）
     * @author zhaodc
     * @since 2026-09-23
     */
    private void checkOptionalIdentifier(String name, String label) {
        if (name == null || name.isBlank()) {
            return;
        }
        jdbc.checkIdentifier(name, label, true);
    }

    private void apply(AppIdSource entity, IdSourceBody body) {
        entity.setName(body.getName().trim());
        entity.setDbType(DbKind.of(body.getDbType()).name());
        entity.setHost(body.getHost().trim());
        entity.setPort(body.getPort());
        entity.setDbName(body.getDbName().trim());
        entity.setUsername(body.getUsername().trim());
        entity.setTableName(blankToNull(body.getTableName()));
        entity.setSequenceName(blankToNull(body.getSequenceName()));
        entity.setAutoInit(Boolean.FALSE.equals(body.getAutoInit()) ? 0 : 1);
        entity.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        entity.setRemark(body.getRemark());
    }

    private AppIdSource require(Long id) {
        if (id == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_NOT_FOUND);
        }
        AppIdSource source = mapper.selectById(id);
        if (source == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_NOT_FOUND, "取号数据源不存在：" + id);
        }
        return source;
    }

    private IdSourceProvider providerOf(AppIdSource source) {
        IdSourceProvider provider = providers.get(DbKind.of(source.getDbType()));
        if (provider == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_SCHEME_MISMATCH,
                    "该库类型暂不支持取号：" + source.getDbType());
        }
        return provider;
    }

    private IdSourceSpec specOf(AppIdSource source) {
        return new IdSourceSpec(source.getId(), source.getName(), DbKind.of(source.getDbType()),
                source.getHost(), source.getPort(), source.getDbName(), source.getUsername(),
                cipher.decrypt(source.getPasswordCipher()),
                source.getTableName(), source.getSequenceName());
    }

    private IdSourceVO toVO(AppIdSource source) {
        IdSourceVO vo = new IdSourceVO();
        vo.setId(String.valueOf(source.getId()));
        vo.setName(source.getName());
        DbKind kind = DbKind.of(source.getDbType());
        vo.setDbType(kind == null ? source.getDbType() : kind.name());
        vo.setDbTypeLabel(kind == null ? source.getDbType() : kind.label());
        vo.setHost(source.getHost());
        vo.setPort(source.getPort());
        vo.setDbName(source.getDbName());
        vo.setUsername(source.getUsername());
        vo.setPasswordMasked(SecretCipher.MASK);
        vo.setTableName(source.getTableName());
        vo.setSequenceName(source.getSequenceName());
        vo.setAutoInit(source.getAutoInit() != null && source.getAutoInit() == 1);
        vo.setStatus(source.getStatus() != null && source.getStatus() == 1);
        vo.setRemark(source.getRemark());
        vo.setCipherReady(cipher.available());
        vo.setCreatedAt(source.getCreatedAt());
        return vo;
    }

    /** 取号对象名（按库类型回落到默认值） */
    private String targetOf(AppIdSource source, DbKind kind) {
        if (kind == DbKind.MYSQL) {
            return blankToNull(source.getTableName()) == null
                    ? MysqlIdSource.DEFAULT_TABLE : source.getTableName();
        }
        if (kind == DbKind.POSTGRESQL) {
            return blankToNull(source.getSequenceName()) == null
                    ? PostgresIdSource.DEFAULT_SEQUENCE : source.getSequenceName();
        }
        return null;
    }

    private void requireCipher() {
        if (!cipher.available()) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_KEY_MISSING);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供 Controller 组装「初始化结果」用：取号对象名 */
    public Map<String, String> targetMap(Long sourceId) {
        AppIdSource source = require(sourceId);
        DbKind kind = DbKind.of(source.getDbType());
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target", targetOf(source, kind));
        map.put("dbType", kind == null ? source.getDbType() : kind.name());
        return map;
    }
}
