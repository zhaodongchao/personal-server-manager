package com.serverpanel.tools.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.serverpanel.appstack.job.JobHttpClient;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.service.SysConfigService;
import com.serverpanel.tools.entity.BasedataSyncLog;
import com.serverpanel.tools.entity.SysBankBin;
import com.serverpanel.tools.entity.SysPhoneSegment;
import com.serverpanel.tools.entity.SysRegion;
import com.serverpanel.tools.mapper.BasedataSyncLogMapper;
import com.serverpanel.tools.mapper.SysBankBinMapper;
import com.serverpanel.tools.mapper.SysPhoneSegmentMapper;
import com.serverpanel.tools.mapper.SysRegionMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 基础数据同步编排：拉取 → 解析 → 分批 UPSERT → 软删缺失行 → 写同步日志。
 *
 * <p>三类数据的同步共用一套编排：
 * <ul>
 *   <li><b>region</b>：国家统计局口径 4 级分文件（provinces/cities/areas/streets），
 *       行数护栏防数据源异常误清空；本地存在但源缺失的 code 置 status=0 软删
 *       （老身份证历史区划仍可解析）；</li>
 *   <li><b>phone / bin</b>：外部源可选，未配置时跳过（内置种子已可用）。</li>
 * </ul>
 *
 * <p>护栏：AtomicBoolean 按 type 防重入；拉取经 JobHttpClient（SSRF 黑名单 +
 * 20MB 上限）；格式异常一律中止且不清库。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasedataSyncService {

    /** region 数据源响应上限（street.json 约 2-3MB，留足余量） */
    private static final int REGION_MAX_BYTES = 20 * 1024 * 1024;

    /** 分批 UPSERT 批大小 */
    private static final int BATCH_SIZE = 500;

    /** 拉取超时秒数（JobHttpClient 内部再夹到 60s） */
    private static final int FETCH_TIMEOUT_SEC = 60;

    private static final String TYPE_REGION = "region";
    private static final String TYPE_PHONE = "phone";
    private static final String TYPE_BIN = "bin";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JobHttpClient jobHttpClient;

    private final SysConfigService configService;

    private final SysRegionMapper regionMapper;

    private final SysPhoneSegmentMapper phoneSegmentMapper;

    private final SysBankBinMapper bankBinMapper;

    private final BasedataSyncLogMapper syncLogMapper;

    /** 类型级防重入闸门 */
    private final Map<String, AtomicBoolean> running = new HashMap<>(Map.of(
            TYPE_REGION, new AtomicBoolean(),
            TYPE_PHONE, new AtomicBoolean(),
            TYPE_BIN, new AtomicBoolean()));

    /**
     * 手动触发（异步执行，立即返回；前端轮询 /sync/status）。
     *
     * @param type region/phone/bin
     */
    public void triggerAsync(String type) {
        String normalized = normalizeType(type);
        AtomicBoolean gate = running.get(normalized);
        if (!gate.compareAndSet(false, true)) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_IN_PROGRESS);
        }
        Thread.startVirtualThread(() -> {
            try {
                sync(normalized, 1);
            } finally {
                gate.set(false);
            }
        });
    }

    /**
     * 同步全部（定时任务入口；依次 region → phone → bin，单类失败不影响后续）。
     *
     * @return 汇总信息（供任务日志展示）
     */
    public String syncAll() {
        List<String> results = new ArrayList<>();
        for (String type : List.of(TYPE_REGION, TYPE_PHONE, TYPE_BIN)) {
            AtomicBoolean gate = running.get(type);
            if (!gate.compareAndSet(false, true)) {
                results.add(type + ": 进行中，跳过");
                continue;
            }
            try {
                results.add(type + ": " + sync(type, 2));
            } finally {
                gate.set(false);
            }
        }
        purgeExpiredLogs();
        return String.join("；", results);
    }

    /**
     * 执行单类型同步（调用方已持有闸门）。
     *
     * @param triggerType 1手动 2定时
     * @return 结果摘要
     */
    private String sync(String type, int triggerType) {
        BasedataSyncLog logEntry = new BasedataSyncLog();
        logEntry.setDataType(type);
        logEntry.setTriggerType(triggerType);
        logEntry.setStatus(0);
        logEntry.setStartedAt(LocalDateTime.now());
        syncLogMapper.insert(logEntry);
        try {
            String message = switch (type) {
                case TYPE_REGION -> syncRegion(logEntry);
                case TYPE_PHONE -> syncPhone();
                case TYPE_BIN -> syncBin();
                default -> throw new ServiceException(ErrorCode.TOOLS_CERT_TYPE_UNSUPPORTED);
            };
            logEntry.setStatus(1);
            logEntry.setMessage(truncate(message));
            log.info("基础数据同步完成 {}：{}", type, message);
            return message;
        } catch (RuntimeException e) {
            logEntry.setStatus(2);
            logEntry.setMessage(truncate(e.getMessage()));
            log.warn("基础数据同步失败 {}：{}", type, e.getMessage());
            throw e;
        } finally {
            logEntry.setFinishedAt(LocalDateTime.now());
            syncLogMapper.updateById(logEntry);
        }
    }

    // ==================== region ====================

    /**
     * 行政区划同步：4 级分文件拉取 → 校验 → 全表置 0 → 批量 UPSERT 置 1 → 统计软删。
     */
    private String syncRegion(BasedataSyncLog logEntry) {
        String template = configService.getValue("tools.basedata.region.source-url", "");
        if (template == null || template.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_SOURCE_UNREACHABLE.getCode(),
                    "未配置区划数据源（tools.basedata.region.source-url）");
        }
        long existingBefore = regionMapper.selectCount(new LambdaQueryWrapper<SysRegion>()
                .eq(SysRegion::getStatus, 1));
        List<SysRegion> rows = new ArrayList<>();
        for (String level : List.of("provinces", "cities", "areas", "streets")) {
            String url = template.replace("{level}", level);
            JsonNode array = fetchJsonArray(url, REGION_MAX_BYTES);
            for (JsonNode item : array) {
                JsonNode codeNode = item.path("code");
                JsonNode nameNode = item.path("name");
                if (!codeNode.isString() || !nameNode.isString()
                        || codeNode.asText().isBlank() || nameNode.asText().isBlank()) {
                    throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                            "区划数据源格式异常（" + level + "：条目缺少 code/name）");
                }
                rows.add(toRegion(codeNode.asText(), nameNode.asText()));
            }
        }
        // 行数护栏：拉取行数低于现有 60% 视为数据源异常，中止防误清空
        if (existingBefore > 0 && rows.size() < existingBefore * 0.6) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                    "源数据仅 " + rows.size() + " 行，低于现有 " + existingBefore + " 行的 60%，已中止（防误清空）");
        }
        if (rows.isEmpty()) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                    "源数据为空，已中止（防误清空）");
        }
        // 先全表置 0（软删），UPSERT 时按新数据置 1 —— 执行完仍为 0 的即被撤销的区划
        regionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysRegion>()
                .eq(SysRegion::getStatus, 1)
                .set(SysRegion::getStatus, 0));
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            regionMapper.batchUpsert(rows.subList(i, Math.min(rows.size(), i + BATCH_SIZE)));
        }
        long activeAfter = regionMapper.selectCount(new LambdaQueryWrapper<SysRegion>()
                .eq(SysRegion::getStatus, 1));
        int inserted = (int) Math.max(0, activeAfter - existingBefore);
        int updated = (int) Math.max(0, activeAfter - inserted);
        int disabled = (int) Math.max(0, existingBefore - updated);
        logEntry.setRowsTotal(rows.size());
        logEntry.setRowsInserted(inserted);
        logEntry.setRowsUpdated(updated);
        logEntry.setRowsDisabled(disabled);
        return "共 " + rows.size() + " 行（新增 " + inserted + " / 更新 " + updated
                + " / 软删 " + disabled + "）";
    }

    /**
     * 源条目 → 实体：code 长度定层级，父级取显式字段或按长度前缀推导。
     */
    private SysRegion toRegion(String code, String name) {
        int level = switch (code.length()) {
            case 2 -> 1;
            case 4 -> 2;
            case 6 -> 3;
            case 9 -> 4;
            default -> throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                    "区划代码长度不合法：" + code);
        };
        SysRegion region = new SysRegion();
        region.setId(IdWorker.getId());
        region.setCode(code);
        region.setName(name);
        region.setLevel(level);
        region.setStatus(1);
        region.setParentCode(switch (level) {
            case 1 -> "";
            case 2 -> code.substring(0, 2);
            case 3 -> code.substring(0, 4);
            default -> code.substring(0, 6);
        });
        return region;
    }

    // ==================== phone / bin ====================

    /**
     * 手机号段同步（外部源可选）：数组条目 code/prefix + operator + segType。
     */
    private String syncPhone() {
        String url = configService.getValue("tools.basedata.phone.source-url", "");
        if (url == null || url.isBlank()) {
            return "未配置外部号段源，仅用内置种子";
        }
        JsonNode array = fetchJsonArray(url, REGION_MAX_BYTES);
        int total = 0;
        for (JsonNode item : array) {
            String prefix = textOr(item, "prefix", "code");
            String operator = textOr(item, "operator", "name");
            if (prefix == null || operator == null) {
                throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                        "号段数据源格式异常（条目缺少 prefix/operator）");
            }
            SysPhoneSegment existing = phoneSegmentMapper.selectOne(
                    new LambdaQueryWrapper<SysPhoneSegment>().eq(SysPhoneSegment::getPrefix, prefix));
            SysPhoneSegment entity = existing == null ? new SysPhoneSegment() : existing;
            entity.setPrefix(prefix);
            entity.setOperator(operator);
            entity.setSegType(item.path("segType").asInt(1));
            if (existing == null) {
                phoneSegmentMapper.insert(entity);
            } else {
                phoneSegmentMapper.updateById(entity);
            }
            total++;
        }
        return "共同步 " + total + " 条号段";
    }

    /**
     * BIN 同步（外部源可选）：数组条目 code/bin + bankName + 可选 cardType/cardLen。
     */
    private String syncBin() {
        String url = configService.getValue("tools.basedata.bin.source-url", "");
        if (url == null || url.isBlank()) {
            return "未配置外部 BIN 源，仅用内置种子";
        }
        JsonNode array = fetchJsonArray(url, REGION_MAX_BYTES);
        int total = 0;
        for (JsonNode item : array) {
            String bin = textOr(item, "bin", "code");
            String bankName = textOr(item, "bankName", "name");
            if (bin == null || bankName == null) {
                throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                        "BIN 数据源格式异常（条目缺少 bin/bankName）");
            }
            SysBankBin existing = bankBinMapper.selectOne(
                    new LambdaQueryWrapper<SysBankBin>().eq(SysBankBin::getBin, bin));
            SysBankBin entity = existing == null ? new SysBankBin() : existing;
            entity.setBin(bin);
            entity.setBankName(bankName);
            entity.setBankShort(textOr(item, "bankShort", "shortName"));
            entity.setCardType(item.path("cardType").asInt(1));
            entity.setCardLen(item.path("cardLen").asInt(item.path("length").asInt(19)));
            if (existing == null) {
                bankBinMapper.insert(entity);
            } else {
                bankBinMapper.updateById(entity);
            }
            total++;
        }
        return "共同步 " + total + " 条 BIN";
    }

    // ==================== 查询与工具 ====================

    /** 拉取并解析 JSON 数组（经 JobHttpClient：SSRF 护栏 + 自定义上限） */
    private JsonNode fetchJsonArray(String url, int maxBytes) {
        JobHttpClient.Response response;
        try {
            response = jobHttpClient.send("GET", url, Map.of(), null, null, FETCH_TIMEOUT_SEC, maxBytes);
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_SOURCE_UNREACHABLE.getCode(),
                    "数据源不可达：" + e.getMessage());
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_SOURCE_UNREACHABLE.getCode(),
                    "数据源返回 HTTP " + response.status());
        }
        try {
            JsonNode tree = JSON.readTree(response.body());
            if (!tree.isArray() || tree.isEmpty()) {
                throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                        "数据源不是非空 JSON 数组");
            }
            return tree;
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServiceException(ErrorCode.TOOLS_BD_SYNC_FORMAT_INVALID.getCode(),
                    "数据源 JSON 解析失败：" + e.getMessage());
        }
    }

    /** 按状态汇总 + 最近一次同步（状态卡数据源） */
    public com.serverpanel.tools.dto.SyncStatusVO status() {
        List<com.serverpanel.tools.dto.SyncStatusVO.TypeStatus> types = new ArrayList<>();
        for (String type : List.of(TYPE_REGION, TYPE_PHONE, TYPE_BIN)) {
            long rows = switch (type) {
                case TYPE_REGION -> regionMapper.selectCount(new LambdaQueryWrapper<SysRegion>()
                        .eq(SysRegion::getStatus, 1));
                case TYPE_PHONE -> phoneSegmentMapper.selectCount(null);
                default -> bankBinMapper.selectCount(null);
            };
            BasedataSyncLog last = syncLogMapper.selectOne(new LambdaQueryWrapper<BasedataSyncLog>()
                    .eq(BasedataSyncLog::getDataType, type)
                    .ne(BasedataSyncLog::getStatus, 0)
                    .orderByDesc(BasedataSyncLog::getStartedAt)
                    .last("LIMIT 1"));
            types.add(new com.serverpanel.tools.dto.SyncStatusVO.TypeStatus(
                    type, rows, rows > 0, running.get(type).get(),
                    last == null ? null : last.getFinishedAt(),
                    last == null ? null : last.getStatus(),
                    last == null ? null : last.getMessage()));
        }
        return new com.serverpanel.tools.dto.SyncStatusVO(types);
    }

    /** 同步日志分页 */
    public com.serverpanel.common.core.PageResult<BasedataSyncLog> logPage(
            com.serverpanel.common.core.PageQuery query, String dataType) {
        var page = syncLogMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<BasedataSyncLog>()
                        .eq(dataType != null && !dataType.isBlank(), BasedataSyncLog::getDataType, dataType)
                        .orderByDesc(BasedataSyncLog::getStartedAt));
        return com.serverpanel.common.core.PageResult.of(page.getRecords(), page.getTotal(),
                query.getPageNum(), query.getPageSize());
    }

    /** 清理超期同步日志（保留天数取 sys_config，默认 90） */
    private void purgeExpiredLogs() {
        int days = Integer.parseInt(configService.getValue(
                "tools.basedata.sync.log-retention-days", "90"));
        syncLogMapper.delete(new LambdaQueryWrapper<BasedataSyncLog>()
                .lt(BasedataSyncLog::getStartedAt, LocalDateTime.now().minusDays(days)));
    }

    private String normalizeType(String type) {
        if (TYPE_REGION.equals(type) || TYPE_PHONE.equals(type) || TYPE_BIN.equals(type)) {
            return type;
        }
        throw new ServiceException(ErrorCode.TOOLS_CERT_TYPE_UNSUPPORTED.getCode(), "不支持的同步类型：" + type);
    }

    private String textOr(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isString() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
