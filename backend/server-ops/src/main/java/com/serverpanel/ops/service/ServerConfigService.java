package com.serverpanel.ops.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.HostResult;
import com.serverpanel.framework.mongo.MongoBaseService;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.ops.dto.ServerConfigApplyVO;
import com.serverpanel.ops.dto.ServerConfigCategoryVO;
import com.serverpanel.ops.dto.ServerConfigChangeVO;
import com.serverpanel.ops.dto.ServerConfigItemBody;
import com.serverpanel.ops.dto.ServerConfigItemVO;
import com.serverpanel.ops.dto.ServerConfigPreviewVO;
import com.serverpanel.ops.entity.mongo.OpsServerConfigCategory;
import com.serverpanel.ops.entity.mongo.OpsServerConfigChange;
import com.serverpanel.ops.entity.mongo.OpsServerConfigItem;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务器配置管理服务。
 *
 * <p>形态：<b>配置生成型</b>——页面录入意图 → 存 MongoDB → 渲染成托管片段 →
 * 经宿主通道（psm-hostagent，root）写盘并校验生效 → 全量留痕、可按历史一键恢复。
 * 与 Nginx 管理模块同构，但配置对象换成 Linux 主机自身：sysctl / limits / sshd / timesync。
 *
 * <h2>「一键生效」9 道闸门（本类的核心）</h2>
 * <ol>
 *   <li>① 前端校验：值类型 / 范围 / 枚举即时校验（前端负责）</li>
 *   <li>② Schema 与黑名单：{@link #checkRules} + {@link #validateValue}，含 SSH「不得关光所有认证方式」等防自锁规则</li>
 *   <li>③ 预演 dry-run：宿主侧 {@code sys.validate}（对临时文件跑 {@code sshd -t}，<b>不触碰正式路径</b>）</li>
 *   <li>④ 用户确认：L3（sshd）必须键入关键字，否则 {@link ErrorCode#SERVER_CONFIG_SELF_LOCKOUT_RISK}</li>
 *   <li>⑤⑥⑦⑧⑨ 备份 → 原子写 → 权威校验 → 生效 → 后置回读：由宿主代理 {@code sys.apply}
 *       在一次调用内完成，<b>任一步失败即恢复备份并重新生效</b>，返回逐阶段明细</li>
 * </ol>
 *
 * <p><b>顺序原则</b>：所有校验闸门都在写盘之前；失败也会落一条历史（result=FAILED/ROLLED_BACK），
 * 保证危险操作一定有痕。恢复（RESTORE）复用同一条链路，只有生效成功后才把配置项写回 Mongo，
 * 因此「恢复失败」不会把数据库里的当前配置改坏。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerConfigService {

    private static final String COL_CATEGORY = "ops_server_config_category";

    private static final String COL_ITEM = "ops_server_config_item";

    private static final String COL_CHANGE = "ops_server_config_change";

    /** 单个配置值长度上限 */
    private static final int MAX_VALUE_LEN = 512;

    /** 渲染内容上限，与宿主代理保持一致（256KB） */
    private static final int MAX_CONTENT_BYTES = 262144;

    /** diff 计算的行数上限（超过则跳过，避免 O(n·m) 爆炸） */
    private static final int MAX_DIFF_LINES = 4000;

    /** 每类别生效互斥锁（同类别同时只允许一个生效/恢复操作） */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final MongoBaseService mongo;

    private final HostChannelService hostChannel;

    // ==================================================================== //
    //                                 类别                                  //
    // ==================================================================== //

    /** 类别列表 + 宿主能力（可用/灰显 + 原因） */
    public List<ServerConfigCategoryVO> categories() {
        Map<String, Map<String, Object>> detected = detectMap();
        List<OpsServerConfigCategory> cats = mongo.find(
                new Query().with(Sort.by(Sort.Direction.ASC, "sort")),
                OpsServerConfigCategory.class, COL_CATEGORY);
        List<ServerConfigCategoryVO> out = new ArrayList<>(cats.size());
        for (OpsServerConfigCategory c : cats) {
            if (c.getStatus() != null && c.getStatus() == 0) {
                continue;
            }
            out.add(toCategoryVO(c, detected.get(c.getCategoryKey())));
        }
        return out;
    }

    /** 主动重探宿主能力（安装/升级宿主代理后无需重启面板） */
    public List<ServerConfigCategoryVO> detect() {
        try {
            hostChannel.refresh();
        } catch (RuntimeException e) {
            log.warn("重探宿主能力失败：{}", e.getMessage());
        }
        return categories();
    }

    private ServerConfigCategoryVO toCategoryVO(OpsServerConfigCategory c, Map<String, Object> d) {
        ServerConfigCategoryVO vo = new ServerConfigCategoryVO();
        vo.setCategoryKey(c.getCategoryKey());
        vo.setName(c.getName());
        vo.setDescription(c.getDescription());
        vo.setRiskLevel(c.getRiskLevel());
        vo.setApplyHint(c.getApplyHint());
        vo.setSort(c.getSort());
        vo.setManagedFile(c.getManagedFile());
        boolean l3 = isL3(c.getRiskLevel());
        vo.setApplyKeyword(l3 ? applyKeyword(c.getCategoryKey()) : null);
        if (d == null) {
            vo.setAvailable(Boolean.FALSE);
            vo.setManaged(Boolean.FALSE);
            vo.setUnavailableReason("宿主通道不可用，或代理未返回该类别能力");
        } else {
            vo.setAvailable(Boolean.TRUE.equals(d.get("available")));
            vo.setManaged(Boolean.TRUE.equals(d.get("managed")));
            if (d.get("file") != null) {
                vo.setManagedFile(String.valueOf(d.get("file")));
            }
            if (d.get("source") != null) {
                vo.setSourceFile(String.valueOf(d.get("source")));
            }
            if (d.get("provider") != null) {
                vo.setProvider(String.valueOf(d.get("provider")));
            }
            if (d.get("reason") != null) {
                vo.setUnavailableReason(String.valueOf(d.get("reason")));
            }
        }
        List<OpsServerConfigItem> rows = listRaw(c.getCategoryKey());
        vo.setItemCount((long) rows.size());
        vo.setManagedItemCount((long) managedItemCount(rows));
        return vo;
    }

    /** 宿主能力探测：{@code sys.detect}，失败时返回空表（页面据此整体灰显） */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> detectMap() {
        try {
            HostResult r = hostChannel.call("sys.detect", Map.of(), "探测宿主配置能力", 30);
            Object cats = r.dataGet("categories");
            if (cats instanceof Map<?, ?> m) {
                Map<String, Map<String, Object>> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> vm) {
                        out.put(String.valueOf(e.getKey()), (Map<String, Object>) vm);
                    }
                }
                return out;
            }
        } catch (RuntimeException e) {
            log.warn("探测宿主配置能力失败：{}", e.getMessage());
        }
        return Map.of();
    }

    /** 取类别，不存在抛 6020 */
    public OpsServerConfigCategory requireCategory(String categoryKey) {
        String key = nvl(categoryKey).trim();
        return mongo.findOne(Query.query(Criteria.where("categoryKey").is(key)),
                        OpsServerConfigCategory.class, COL_CATEGORY)
                .orElseThrow(() -> new ServiceException(ErrorCode.SERVER_CONFIG_CATEGORY_NOT_FOUND));
    }

    private Map<String, String> categoryNames() {
        Map<String, String> names = new HashMap<>();
        List<OpsServerConfigCategory> cats = mongo.find(new Query(),
                OpsServerConfigCategory.class, COL_CATEGORY);
        for (OpsServerConfigCategory c : cats) {
            names.put(c.getCategoryKey(), c.getName());
        }
        return names;
    }

    // ==================================================================== //
    //                             配置项 CRUD                              //
    // ==================================================================== //

    /** 该类别配置项列表（托管值 + 当前生效值 + 推荐值） */
    public List<ServerConfigItemVO> items(String categoryKey) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        List<OpsServerConfigItem> rows = listRaw(cat.getCategoryKey());
        Map<String, String> effective = probe(cat.getCategoryKey(), rows);
        List<ServerConfigItemVO> out = new ArrayList<>(rows.size());
        for (OpsServerConfigItem r : rows) {
            out.add(toItemVO(r, effective.get(probeKey(cat.getCategoryKey(), r.getItemKey()))));
        }
        return out;
    }

    public ServerConfigItemVO createItem(String categoryKey, ServerConfigItemBody body) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        String key = nvl(body.getItemKey()).trim();
        if (key.isEmpty()) {
            throw invalid("参数名不能为空");
        }
        if (key.length() > 128) {
            throw invalid("参数名过长（上限 128 字符）");
        }
        if (key.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '\t')) {
            throw invalid("参数名不能包含换行或制表符");
        }
        String value = trimToNull(body.getItemValue());
        validateValue(cat.getCategoryKey(), key, value);
        if (findItem(cat.getCategoryKey(), key) != null) {
            throw invalid("该参数已存在：" + key);
        }
        LocalDateTime now = LocalDateTime.now();
        OpsServerConfigItem row = new OpsServerConfigItem();
        row.setId(new ObjectId().toHexString());
        row.setCategoryKey(cat.getCategoryKey());
        row.setItemKey(key);
        row.setItemValue(value);
        row.setValueType(hnas(body.getValueType()) ? body.getValueType().trim()
                : guessType(cat.getCategoryKey(), key));
        row.setOptions(body.getOptions());
        row.setRecommended(trimToNull(body.getRecommended()));
        row.setDescription(trimToNull(body.getDescription()));
        row.setSort(body.getSort() == null ? nextSort(cat.getCategoryKey()) : body.getSort());
        row.setBuiltin(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mongo.save(row, COL_ITEM);
        return toItemVO(row, null);
    }

    public ServerConfigItemVO updateItem(String categoryKey, String itemKey, ServerConfigItemBody body) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        OpsServerConfigItem row = findItem(cat.getCategoryKey(), nvl(itemKey).trim());
        if (row == null) {
            throw invalid("配置项不存在：" + itemKey);
        }
        String value = trimToNull(body.getItemValue());
        validateValue(cat.getCategoryKey(), row.getItemKey(), value);
        row.setItemValue(value);
        if (hnas(body.getValueType())) {
            row.setValueType(body.getValueType().trim());
        }
        if (body.getOptions() != null) {
            row.setOptions(body.getOptions());
        }
        if (body.getRecommended() != null) {
            row.setRecommended(trimToNull(body.getRecommended()));
        }
        if (body.getDescription() != null) {
            row.setDescription(trimToNull(body.getDescription()));
        }
        if (body.getSort() != null) {
            row.setSort(body.getSort());
        }
        row.setUpdatedAt(LocalDateTime.now());
        mongo.save(row, COL_ITEM);
        return toItemVO(row, null);
    }

    public void deleteItem(String categoryKey, String itemKey) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        OpsServerConfigItem row = findItem(cat.getCategoryKey(), nvl(itemKey).trim());
        if (row == null) {
            throw invalid("配置项不存在：" + itemKey);
        }
        mongo.remove(Query.query(Criteria.where("_id").is(row.getId())), COL_ITEM);
    }

    private List<OpsServerConfigItem> listRaw(String categoryKey) {
        Query q = Query.query(Criteria.where("categoryKey").is(categoryKey))
                .with(Sort.by(Sort.Direction.ASC, "sort").and(Sort.by(Sort.Direction.ASC, "itemKey")));
        return mongo.find(q, OpsServerConfigItem.class, COL_ITEM);
    }

    private OpsServerConfigItem findItem(String categoryKey, String itemKey) {
        if (itemKey == null || itemKey.isEmpty()) {
            return null;
        }
        return mongo.findOne(Query.query(Criteria.where("categoryKey").is(categoryKey)
                                .and("itemKey").is(itemKey)),
                        OpsServerConfigItem.class, COL_ITEM)
                .orElse(null);
    }

    private int nextSort(String categoryKey) {
        int max = 0;
        for (OpsServerConfigItem r : listRaw(categoryKey)) {
            if (r.getSort() != null && r.getSort() > max) {
                max = r.getSort();
            }
        }
        return max + 10;
    }

    private ServerConfigItemVO toItemVO(OpsServerConfigItem r, String effective) {
        ServerConfigItemVO vo = new ServerConfigItemVO();
        vo.setId(r.getId());
        vo.setCategoryKey(r.getCategoryKey());
        vo.setItemKey(r.getItemKey());
        vo.setItemValue(r.getItemValue());
        vo.setEffectiveValue(effective);
        vo.setDefaultValue(r.getDefaultValue());
        vo.setValueType(r.getValueType());
        vo.setOptions(r.getOptions());
        vo.setRecommended(r.getRecommended());
        vo.setDescription(r.getDescription());
        vo.setSort(r.getSort());
        vo.setBuiltin(r.getBuiltin());
        return vo;
    }

    // ==================================================================== //
    //                                 预演                                 //
    // ==================================================================== //

    /** 一键生效预演：渲染全文 + 与当前托管文件的 diff + 宿主机 dry-run 校验（只读不落盘） */
    public ServerConfigPreviewVO preview(String categoryKey) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        String key = cat.getCategoryKey();
        List<OpsServerConfigItem> rows = listRaw(key);
        String content = render(key, rows);
        Map<String, Object> managed = readManaged(key);
        String current = managed.get("content") == null ? null : String.valueOf(managed.get("content"));

        List<String> errors = new ArrayList<>();
        errors.addAll(checkRules(key, rows));
        if (content.isEmpty()) {
            errors.add("没有任何配置项被托管（所有值均为空），无法生效");
        } else if (bytes(content) > MAX_CONTENT_BYTES) {
            errors.add("渲染内容超过 256KB 上限");
        }

        ServerConfigPreviewVO vo = new ServerConfigPreviewVO();
        vo.setCategoryKey(key);
        vo.setRiskLevel(cat.getRiskLevel());
        boolean l3 = isL3(cat.getRiskLevel());
        vo.setNeedConfirm(l3);
        vo.setApplyKeyword(l3 ? applyKeyword(key) : null);
        vo.setContent(content);
        vo.setCurrentContent(current);
        vo.setTargetPath(managed.get("path") == null ? cat.getManagedFile()
                : String.valueOf(managed.get("path")));
        vo.setDiff(diffText(current, content));
        vo.setManagedItemCount(managedItemCount(rows));
        vo.setChanged(!content.equals(nvl(current)));
        vo.setRuleErrors(errors);
        vo.setWarnings(categoryWarnings(key));

        if (!errors.isEmpty()) {
            vo.setValidateOk(Boolean.FALSE);
            vo.setValidateOutput(String.join("；", errors));
        } else {
            Map<String, Object> args = new HashMap<>();
            args.put("categoryKey", key);
            args.put("content", content);
            try {
                HostResult r = hostChannel.call("sys.validate", args, "预演校验", 60);
                vo.setValidateOk(Boolean.TRUE.equals(r.dataGet("ok")));
                vo.setValidateOutput(r.dataString("output"));
            } catch (RuntimeException e) {
                vo.setValidateOk(Boolean.FALSE);
                vo.setValidateOutput("预演校验调用失败：" + e.getMessage());
            }
        }
        return vo;
    }

    // ==================================================================== //
    //                              一键生效                                //
    // ==================================================================== //

    /** 一键生效（L3 类别需 {@code confirm} 键入关键字） */
    public ServerConfigApplyVO apply(String categoryKey, String confirm) {
        OpsServerConfigCategory cat = requireCategory(categoryKey);
        List<OpsServerConfigItem> rows = listRaw(cat.getCategoryKey());
        return doApply(cat, rows, "APPLY", confirm, null);
    }

    /**
     * 生效主链路（9 道闸门），同时服务于 APPLY 与 RESTORE。
     *
     * @param persistAfter 非空时表示恢复场景：生效成功后把这份配置项写回 Mongo
     */
    private ServerConfigApplyVO doApply(OpsServerConfigCategory cat, List<OpsServerConfigItem> rows,
                                        String op, String confirm, List<OpsServerConfigItem> persistAfter) {
        String key = cat.getCategoryKey();
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ServiceException(ErrorCode.SERVER_CONFIG_BUSY.getCode(),
                    "配置类别「" + nvl(cat.getName()) + "」正在生效中，请稍后重试");
        }
        long started = System.currentTimeMillis();
        try {
            String content = render(key, rows);
            String beforeItems = serializeItems(rows);
            Map<String, Object> managed = readManaged(key);
            String before = managed.get("content") == null ? null : String.valueOf(managed.get("content"));
            String diff = diffText(before, content);

            // ---- 闸门②：服务端 schema / 黑名单 / 语义规则 ----
            List<String> errors = new ArrayList<>(checkRules(key, rows));
            if (content.isEmpty()) {
                errors.add("没有任何配置项被托管（所有值均为空），无法生效");
            } else if (bytes(content) > MAX_CONTENT_BYTES) {
                errors.add("渲染内容超过 256KB 上限");
            }
            if (!errors.isEmpty()) {
                String msg = String.join("；", errors);
                record(cat, op, before, content, diff, msg, null, null, 0L, null,
                        beforeItems, beforeItems, "FAILED", "服务端规则校验未通过");
                throw new ServiceException(ErrorCode.SERVER_CONFIG_ITEM_INVALID.getCode(), msg);
            }

            // ---- 闸门④：高风险类别二次确认（键入关键字） ----
            if (isL3(cat.getRiskLevel())) {
                String want = applyKeyword(key);
                if (!want.equals(nvl(confirm).trim())) {
                    String msg = "该类别属高风险变更，需键入「" + want + "」二次确认";
                    record(cat, op, before, content, diff, msg, null, null, 0L, null,
                            beforeItems, beforeItems, "FAILED", msg);
                    throw new ServiceException(ErrorCode.SERVER_CONFIG_SELF_LOCKOUT_RISK.getCode(), msg);
                }
            }

            // ---- 闸门③：宿主机预演（对临时文件校验，不触碰正式路径） ----
            Map<String, Object> vArgs = new HashMap<>();
            vArgs.put("categoryKey", key);
            vArgs.put("content", content);
            HostResult vr = hostChannel.require().call("sys.validate", vArgs, 60);
            String validateOutput = vr.dataGet("output") == null ? "" : String.valueOf(vr.dataGet("output"));
            if (!Boolean.TRUE.equals(vr.dataGet("ok"))) {
                String msg = "预演校验未通过，未做任何变更：" + validateOutput;
                record(cat, op, before, content, diff, validateOutput, null, null, 0L, null,
                        beforeItems, beforeItems, "FAILED", msg);
                throw new ServiceException(ErrorCode.SERVER_CONFIG_VALIDATE_FAILED.getCode(), msg);
            }

            // ---- 闸门⑤~⑨：备份 → 原子写 → 权威校验 → 生效 → 回读（宿主代理内完成，失败自动回滚） ----
            Map<String, Object> aArgs = new HashMap<>();
            aArgs.put("categoryKey", key);
            aArgs.put("content", content);
            HostResult ar = hostChannel.require().call("sys.apply", aArgs, 150);
            Map<String, Object> stages = asMap(ar.dataGet("stages"));
            boolean applied = Boolean.TRUE.equals(ar.dataGet("applied"));
            boolean rolledBack = Boolean.TRUE.equals(ar.dataGet("rolledBack"));
            String backupPath = ar.dataString("backup");
            String stagesText = flatten(stages);
            String applyOutput = stageSummary(stages);
            long ms = System.currentTimeMillis() - started;

            if (!applied) {
                String reason = firstNonBlank(stageError(stages), validateOutput, ar.getStderr());
                String msg = "配置生效失败" + (rolledBack ? "，已自动回滚到变更前状态" : "")
                        + (reason == null ? "" : "：" + reason);
                record(cat, op, before, content, diff, validateOutput, applyOutput, stagesText, ms,
                        backupPath, beforeItems, beforeItems,
                        rolledBack ? "ROLLED_BACK" : "FAILED", msg);
                throw new ServiceException(ErrorCode.SERVER_CONFIG_APPLY_FAILED.getCode(), msg);
            }

            // 成功：恢复场景才写回配置项（保证「恢复失败不改坏当前配置」）
            if (persistAfter != null) {
                persistItems(persistAfter);
            }
            String afterItems = serializeItems(persistAfter == null ? rows : persistAfter);
            String changeId = record(cat, op, before, content, diff, validateOutput,
                    applyOutput, stagesText, ms, backupPath, beforeItems, afterItems, "SUCCESS", null);

            ServerConfigApplyVO vo = new ServerConfigApplyVO();
            vo.setCategoryKey(key);
            vo.setOp(op);
            vo.setApplied(Boolean.TRUE);
            vo.setRolledBack(Boolean.FALSE);
            vo.setMessage("RESTORE".equals(op) ? "配置已按历史恢复并生效" : "配置已生效");
            vo.setChangeId(changeId);
            vo.setBackupPath(backupPath);
            vo.setValidateOutput(validateOutput);
            vo.setApplyOutput(applyOutput);
            vo.setDiff(diff);
            vo.setDurationMs(ms);
            vo.setStages(stages);
            vo.setEffective(asStringMap(ar.dataGet("effective")));
            return vo;
        } finally {
            lock.unlock();
        }
    }

    // ==================================================================== //
    //                          历史 与 按历史恢复                          //
    // ==================================================================== //

    public PageResult<ServerConfigChangeVO> changePage(String categoryKey, int pageNum, int pageSize) {
        int num = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), 200);
        long total = mongo.count(changeQuery(categoryKey), COL_CHANGE);
        Query q = changeQuery(categoryKey)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) (num - 1) * size)
                .limit(size);
        List<OpsServerConfigChange> rows = mongo.find(q, OpsServerConfigChange.class, COL_CHANGE);
        Map<String, String> names = categoryNames();
        List<ServerConfigChangeVO> out = new ArrayList<>(rows.size());
        for (OpsServerConfigChange r : rows) {
            out.add(toChangeVO(r, names.get(r.getCategoryKey()), false));
        }
        return PageResult.of(out, total, num, size);
    }

    public ServerConfigChangeVO changeDetail(String id) {
        OpsServerConfigChange row = mongo.findById(id, OpsServerConfigChange.class, COL_CHANGE)
                .orElseThrow(() -> new ServiceException(ErrorCode.NOT_FOUND.getCode(), "变更记录不存在"));
        return toChangeVO(row, categoryNames().get(row.getCategoryKey()), true);
    }

    /**
     * 按历史一键恢复。
     *
     * @param target {@code before}（回到该次变更之前，默认）/ {@code after}（回到该次变更之后）
     */
    public ServerConfigApplyVO restore(String changeId, String target, String confirm) {
        OpsServerConfigChange change = mongo.findById(changeId, OpsServerConfigChange.class, COL_CHANGE)
                .orElseThrow(() -> new ServiceException(ErrorCode.NOT_FOUND.getCode(), "变更记录不存在"));
        boolean useAfter = "after".equalsIgnoreCase(nvl(target).trim());
        String snapshot = useAfter ? change.getAfterItems() : change.getBeforeItems();
        if (snapshot == null || snapshot.isBlank()) {
            throw new ServiceException(ErrorCode.SERVER_CONFIG_ITEM_INVALID.getCode(),
                    "该条历史没有「" + (useAfter ? "变更后" : "变更前") + "」配置快照，无法恢复");
        }
        OpsServerConfigCategory cat = requireCategory(change.getCategoryKey());
        List<OpsServerConfigItem> snapshotItems = deserializeItems(snapshot);
        List<OpsServerConfigItem> current = listRaw(cat.getCategoryKey());
        Map<String, OpsServerConfigItem> byKey = new LinkedHashMap<>();
        for (OpsServerConfigItem c : current) {
            byKey.put(nvl(c.getItemKey()).trim(), c);
        }
        LocalDateTime now = LocalDateTime.now();
        List<OpsServerConfigItem> targetRows = new ArrayList<>();
        Set<String> snapKeys = new HashSet<>();
        int fallbackSort = nextSort(cat.getCategoryKey());
        for (OpsServerConfigItem s : snapshotItems) {
            String itemKey = nvl(s.getItemKey()).trim();
            if (itemKey.isEmpty()) {
                continue;
            }
            snapKeys.add(itemKey);
            OpsServerConfigItem merged = byKey.get(itemKey);
            OpsServerConfigItem row = new OpsServerConfigItem();
            row.setId(merged == null ? new ObjectId().toHexString() : merged.getId());
            row.setCategoryKey(cat.getCategoryKey());
            row.setItemKey(itemKey);
            row.setItemValue(trimToNull(s.getItemValue()));
            int sortVal = s.getSort() != null ? s.getSort()
                    : (merged == null ? fallbackSort : intOr(merged.getSort(), fallbackSort));
            row.setSort(sortVal);
            row.setValueType(merged == null ? guessType(cat.getCategoryKey(), itemKey) : merged.getValueType());
            row.setOptions(merged == null ? null : merged.getOptions());
            row.setRecommended(merged == null ? null : merged.getRecommended());
            row.setDescription(merged == null ? "由历史恢复生成" : merged.getDescription());
            row.setBuiltin(merged == null ? 0 : intOr(merged.getBuiltin(), 0));
            row.setCreatedAt(merged == null ? now : merged.getCreatedAt());
            row.setUpdatedAt(now);
            targetRows.add(row);
        }
        // 当前有、快照里没有的项：值置空（交还系统默认），不物理删除，避免丢元数据
        for (OpsServerConfigItem c : current) {
            String itemKey = nvl(c.getItemKey()).trim();
            if (snapKeys.contains(itemKey)) {
                continue;
            }
            OpsServerConfigItem row = new OpsServerConfigItem();
            row.setId(c.getId());
            row.setCategoryKey(cat.getCategoryKey());
            row.setItemKey(c.getItemKey());
            row.setItemValue(null);
            row.setSort(c.getSort());
            row.setValueType(c.getValueType());
            row.setOptions(c.getOptions());
            row.setRecommended(c.getRecommended());
            row.setDescription(c.getDescription());
            row.setBuiltin(c.getBuiltin());
            row.setCreatedAt(c.getCreatedAt());
            row.setUpdatedAt(now);
            targetRows.add(row);
        }
        return doApply(cat, targetRows, "RESTORE", confirm, targetRows);
    }

    private Query changeQuery(String categoryKey) {
        return hnas(categoryKey)
                ? Query.query(Criteria.where("categoryKey").is(categoryKey.trim()))
                : new Query();
    }

    private ServerConfigChangeVO toChangeVO(OpsServerConfigChange r, String categoryName, boolean detail) {
        ServerConfigChangeVO vo = new ServerConfigChangeVO();
        vo.setId(r.getId());
        vo.setCategoryKey(r.getCategoryKey());
        vo.setCategoryName(categoryName == null ? r.getCategoryKey() : categoryName);
        vo.setOp(r.getOp());
        vo.setResult(r.getResult());
        vo.setErrorMsg(r.getErrorMsg());
        vo.setOperator(r.getOperator());
        vo.setOperatorIp(r.getOperatorIp());
        vo.setDurationMs(r.getDurationMs());
        vo.setBackupPath(r.getBackupPath());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setDetail(detail);
        if (detail) {
            vo.setBeforeContent(r.getBeforeContent());
            vo.setAfterContent(r.getAfterContent());
            vo.setDiff(r.getDiff());
            vo.setValidateOutput(r.getValidateOutput());
            vo.setApplyOutput(r.getApplyOutput());
            vo.setStagesText(r.getStagesText());
            vo.setBeforeItems(toItemVOs(deserializeItems(r.getBeforeItems())));
            vo.setAfterItems(toItemVOs(deserializeItems(r.getAfterItems())));
        }
        return vo;
    }

    private List<ServerConfigItemVO> toItemVOs(List<OpsServerConfigItem> rows) {
        List<ServerConfigItemVO> out = new ArrayList<>(rows.size());
        for (OpsServerConfigItem r : rows) {
            ServerConfigItemVO vo = new ServerConfigItemVO();
            vo.setItemKey(r.getItemKey());
            vo.setItemValue(r.getItemValue());
            vo.setSort(r.getSort());
            out.add(vo);
        }
        return out;
    }

    /** 落一条变更历史；失败只记日志，不影响主流程结论 */
    private String record(OpsServerConfigCategory cat, String op, String beforeContent, String afterContent,
                          String diff, String validateOutput, String applyOutput, String stagesText,
                          long durationMs, String backupPath, String beforeItems, String afterItems,
                          String result, String errorMsg) {
        try {
            OpsServerConfigChange row = new OpsServerConfigChange();
            row.setId(new ObjectId().toHexString());
            row.setCategoryKey(cat.getCategoryKey());
            row.setOp(op);
            row.setBeforeContent(cut(beforeContent, 300000));
            row.setAfterContent(cut(afterContent, 300000));
            row.setDiff(cut(diff, 300000));
            row.setValidateOutput(cut(validateOutput, 20000));
            row.setApplyOutput(cut(applyOutput, 20000));
            row.setStagesText(cut(stagesText, 20000));
            row.setBeforeItems(cut(beforeItems, 300000));
            row.setAfterItems(cut(afterItems, 300000));
            row.setBackupPath(backupPath);
            row.setDurationMs(durationMs);
            row.setResult(result);
            row.setErrorMsg(cut(errorMsg, 2000));
            row.setOperator(currentOperator());
            row.setOperatorIp(currentIp());
            row.setCreatedAt(LocalDateTime.now());
            mongo.save(row, COL_CHANGE);
            return row.getId();
        } catch (RuntimeException e) {
            log.warn("记录服务器配置变更历史失败：{}", e.getMessage());
            return null;
        }
    }

    private void persistItems(List<OpsServerConfigItem> rows) {
        for (OpsServerConfigItem r : rows) {
            mongo.save(r, COL_ITEM);
        }
    }

    // ==================================================================== //
    //                            渲染 / 校验 / diff                        //
    // ==================================================================== //

    /**
     * 渲染托管片段。空值项整行不写入（= 不托管），因此「清空某项的值」即等于交还系统默认。
     * 返回空串表示该类别当前没有任何被托管的项。
     */
    private String render(String categoryKey, List<OpsServerConfigItem> rows) {
        StringBuilder sb = new StringBuilder();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sb.append("# ==============================================================\n");
        sb.append("# 由 ServerPanel「服务器配置」生成，请勿手工编辑\n");
        sb.append("# 类别：").append(categoryLabel(categoryKey)).append("（").append(categoryKey).append("）\n");
        sb.append("# 生成时间：").append(stamp).append("\n");
        sb.append("# 修改入口：运维工具 → 服务器配置\n");
        sb.append("# ==============================================================\n");
        if ("timesync".equals(categoryKey)) {
            sb.append("\n[Time]\n");
        }
        int wrote = 0;
        for (OpsServerConfigItem it : rows) {
            String key = nvl(it.getItemKey()).trim();
            String val = trimToNull(it.getItemValue());
            if (key.isEmpty() || val == null) {
                continue;
            }
            if (key.startsWith("[") && key.endsWith("]")) {
                continue;
            }
            switch (categoryKey) {
                case "sysctl" -> sb.append(key).append(" = ").append(val).append('\n');
                case "timesync" -> sb.append(key).append('=').append(val).append('\n');
                default -> sb.append(key).append(' ').append(val).append('\n');
            }
            wrote++;
        }
        return wrote == 0 ? "" : sb.toString();
    }

    private String categoryLabel(String key) {
        return switch (key) {
            case "sysctl" -> "内核参数";
            case "limits" -> "资源限制";
            case "sshd" -> "SSH 配置";
            case "timesync" -> "时间同步";
            default -> key;
        };
    }

    /** 类别级 + 逐项校验，返回全部错误（不抛异常，便于预演一次列全） */
    private List<String> checkRules(String categoryKey, List<OpsServerConfigItem> rows) {
        List<String> errors = new ArrayList<>();
        int managed = 0;
        Map<String, String> vals = new HashMap<>();
        for (OpsServerConfigItem it : rows) {
            String value = trimToNull(it.getItemValue());
            if (value == null) {
                continue;
            }
            managed++;
            String key = nvl(it.getItemKey()).trim();
            try {
                validateValue(categoryKey, key, value);
            } catch (ServiceException e) {
                errors.add(e.getMessage());
            }
            vals.put(key.toLowerCase(Locale.ROOT), value);
        }
        if ("sshd".equals(categoryKey)) {
            boolean pwOff = "no".equalsIgnoreCase(vals.get("passwordauthentication"));
            boolean pkOff = "no".equalsIgnoreCase(vals.get("pubkeyauthentication"));
            boolean kbOff = "no".equalsIgnoreCase(vals.get("kbdinteractiveauthentication"));
            if (pwOff && pkOff) {
                errors.add("不能同时关闭 PasswordAuthentication 与 PubkeyAuthentication，否则将无法登录 SSH");
            } else if (pwOff && kbOff) {
                errors.add("已关闭密码登录且同时关闭键盘交互认证，可能导致无法登录 SSH");
            }
        } else if ("timesync".equals(categoryKey)) {
            boolean hasSource = false;
            for (Map.Entry<String, String> e : vals.entrySet()) {
                String k = e.getKey();
                if ("ntp".equals(k) || "fallbackntp".equals(k) || "server".equals(k) || "pool".equals(k)) {
                    hasSource = true;
                    break;
                }
            }
            if (managed > 0 && !hasSource) {
                errors.add("至少需要保留一条 NTP 源（NTP / FallbackNTP），否则时间同步会失去上游");
            }
        } else if ("sysctl".equals(categoryKey)) {
            String ocm = vals.get("vm.overcommit_memory");
            if (ocm != null && !List.of("0", "1", "2").contains(ocm)) {
                errors.add("vm.overcommit_memory 只能是 0 / 1 / 2（当前：" + ocm + "）");
            }
            String portRange = vals.get("net.ipv4.ip_local_port_range");
            if (portRange != null) {
                String[] p = portRange.trim().split("\\s+");
                if (p.length != 2) {
                    errors.add("net.ipv4.ip_local_port_range 需形如「1024 65535」");
                } else {
                    try {
                        int lo = Integer.parseInt(p[0]);
                        int hi = Integer.parseInt(p[1]);
                        if (lo <= 0 || hi > 65535 || lo >= hi) {
                            errors.add("net.ipv4.ip_local_port_range 需满足 0 < 低端口 < 高端口 <= 65535（当前："
                                    + portRange + "）");
                        }
                    } catch (NumberFormatException e) {
                        errors.add("net.ipv4.ip_local_port_range 必须是两个整数");
                    }
                }
            }
            String cc = vals.get("net.ipv4.tcp_congestion_control");
            if (cc != null && cc.contains(" ")) {
                errors.add("net.ipv4.tcp_congestion_control 不能包含空格");
            }
        }
        return errors;
    }

    /** 单项值校验（服务端 schema / 黑名单）；value 为 null 表示不托管，直接放行 */
    private void validateValue(String categoryKey, String itemKey, String value) {
        if (value == null) {
            return;
        }
        if (itemKey == null || itemKey.isBlank()) {
            throw invalid("参数名不能为空");
        }
        if (value.length() > MAX_VALUE_LEN) {
            throw invalid("配置值过长（上限 " + MAX_VALUE_LEN + " 字符）：" + itemKey);
        }
        if (value.chars().anyMatch(c -> c == '\n' || c == '\r')) {
            throw invalid("配置值不能包含换行：" + itemKey);
        }
        switch (categoryKey) {
            case "sysctl" -> {
                if (!itemKey.matches("^[A-Za-z0-9_.-]+$")) {
                    throw invalid("内核参数名不合法（只允许字母数字与 _ . -）：" + itemKey);
                }
                if (value.contains("=")) {
                    throw invalid("内核参数值不能包含 =：" + itemKey);
                }
            }
            case "limits" -> {
                if (!itemKey.matches("^[A-Za-z0-9_*.@-]+\\s+(soft|hard|-)\\s+[A-Za-z0-9_-]+$")) {
                    throw invalid("资源限制参数名需形如「* soft nofile」（域 类型 项目）：" + itemKey);
                }
                String item = itemKey.substring(itemKey.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
                if (item.equals("nofile") || item.equals("nproc") || item.equals("memlock")) {
                    if (!"unlimited".equalsIgnoreCase(value)) {
                        long n = parseLong(itemKey, value);
                        if (n <= 0) {
                            throw invalid(item + " 必须为正整数或 unlimited：" + value);
                        }
                        if (n > 1073741824L) {
                            throw invalid(item + " 的值过大（上限 1073741824）：" + value);
                        }
                    }
                } else if (item.equals("core")) {
                    if (!"unlimited".equalsIgnoreCase(value) && parseLong(itemKey, value) < 0) {
                        throw invalid("core 只能是 0 / 正整数 / unlimited：" + value);
                    }
                }
            }
            case "sshd" -> {
                if (!itemKey.matches("^[A-Za-z][A-Za-z0-9]*$")) {
                    throw invalid("sshd 参数名不合法：" + itemKey);
                }
                String k = itemKey.toLowerCase(Locale.ROOT);
                switch (k) {
                    case "port" -> {
                        int p = (int) parseLong(itemKey, value);
                        if (p < 1 || p > 65535) {
                            throw invalid("Port 必须在 1~65535 之间：" + value);
                        }
                    }
                    case "maxauthtries" -> {
                        long n = parseLong(itemKey, value);
                        if (n < 1 || n > 100) {
                            throw invalid("MaxAuthTries 必须在 1~100 之间：" + value);
                        }
                    }
                    case "permitrootlogin" -> checkEnum(itemKey, value,
                            List.of("yes", "no", "prohibit-password", "forced-commands-only"));
                    case "passwordauthentication", "pubkeyauthentication", "kbdinteractiveauthentication",
                         "challengeresponseauthentication", "usedns", "x11forwarding",
                         "gssapiauthentication", "permitemptypasswords", "strictmodes" ->
                            checkEnum(itemKey, value, List.of("yes", "no"));
                    case "clientaliveinterval", "clientalivecountmax", "logingracetime", "maxsessions" -> {
                        if (parseLong(itemKey, value) < 0) {
                            throw invalid(itemKey + " 不能为负数：" + value);
                        }
                    }
                    default -> {
                        // 其余 sshd 指令交给宿主机 sshd -t 兜底校验
                    }
                }
            }
            case "timesync" -> {
                if (!itemKey.matches("^[A-Za-z][A-Za-z0-9]*$")) {
                    throw invalid("时间同步参数名不合法：" + itemKey);
                }
                if (value.contains("=")) {
                    throw invalid("时间同步参数值不能包含 =：" + itemKey);
                }
            }
            default -> {
                // 未知类别不额外约束（类别本身已由 requireCategory 把关）
            }
        }
    }

    private void checkEnum(String itemKey, String value, List<String> allowed) {
        for (String a : allowed) {
            if (a.equalsIgnoreCase(value)) {
                return;
            }
        }
        throw invalid(itemKey + " 的取值必须是 " + String.join(" / ", allowed) + "（当前：" + value + "）");
    }

    private long parseLong(String itemKey, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(itemKey + " 必须是整数（当前：" + value + "）");
        }
    }

    private List<String> categoryWarnings(String categoryKey) {
        List<String> w = new ArrayList<>();
        switch (categoryKey) {
            case "limits" -> w.add("limits 仅对「新会话 / 新进程」生效，已在运行的服务不会回溯；"
                    + "systemd 服务还需在 unit 中设置 LimitNOFILE。");
            case "sshd" -> w.add("生效会执行 systemctl restart sshd：既有连接保留，"
                    + "请先确认当前密钥可登录，避免把自己锁在外面。");
            case "sysctl" -> w.add("内核参数即时生效并持续到重启；托管片段会随系统启动自动加载。");
            case "timesync" -> w.add("生效会重启时间同步服务，可能存在数秒的时间同步空窗。");
            default -> {
                // 无额外告警
            }
        }
        return w;
    }

    private String diffText(String before, String after) {
        List<String> a = splitLines(before);
        List<String> b = splitLines(after);
        if (a.size() > MAX_DIFF_LINES || b.size() > MAX_DIFF_LINES) {
            return "（内容过大，已省略逐行 diff）";
        }
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                sb.append("  ").append(a.get(i)).append('\n');
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                sb.append("- ").append(a.get(i)).append('\n');
                i++;
            } else {
                sb.append("+ ").append(b.get(j)).append('\n');
                j++;
            }
        }
        while (i < n) {
            sb.append("- ").append(a.get(i++)).append('\n');
        }
        while (j < m) {
            sb.append("+ ").append(b.get(j++)).append('\n');
        }
        return sb.toString();
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return Arrays.asList(t.split("\r?\n", -1));
    }

    // ==================================================================== //
    //                                 宿主读取                             //
    // ==================================================================== //

    /** 读取当前托管文件（content / path / exists）；宿主不可用时返回空表 */
    private Map<String, Object> readManaged(String categoryKey) {
        try {
            HostResult r = hostChannel.call("sys.readManaged",
                    Map.of("categoryKey", categoryKey), "读取托管文件", 30);
            Map<String, Object> out = new HashMap<>();
            out.put("path", r.dataString("path"));
            out.put("source", r.dataString("source"));
            out.put("exists", r.dataBool("exists"));
            out.put("content", r.dataString("content"));
            return out;
        } catch (RuntimeException e) {
            log.debug("读取托管文件失败（{}）：{}", categoryKey, e.getMessage());
            return Map.of();
        }
    }

    /** 读取当前系统生效值（键统一小写，便于与 sshd/timesync 大小写差异对齐） */
    private Map<String, String> probe(String categoryKey, List<OpsServerConfigItem> rows) {
        List<String> keys = new ArrayList<>();
        for (OpsServerConfigItem r : rows) {
            String k = probeKey(categoryKey, r.getItemKey());
            if (!k.isEmpty() && !keys.contains(k)) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("categoryKey", categoryKey);
            args.put("keys", keys);
            HostResult r = hostChannel.call("sys.probe", args, "读取当前生效值", 60);
            Map<String, String> out = new HashMap<>();
            Object eff = r.dataGet("effective");
            if (eff instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT),
                            e.getValue() == null ? null : String.valueOf(e.getValue()));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("读取类别 {} 的生效值失败：{}", categoryKey, e.getMessage());
            return Map.of();
        }
    }

    /** 探测键：limits 的 itemKey 是「域 类型 项目」三段，探测时只取项目名 */
    private String probeKey(String categoryKey, String itemKey) {
        String k = nvl(itemKey).trim();
        if ("limits".equals(categoryKey)) {
            int i = k.lastIndexOf(' ');
            if (i > 0) {
                k = k.substring(i + 1).trim();
            }
        }
        return k.toLowerCase(Locale.ROOT);
    }

    // ==================================================================== //
    //                                 工具                                 //
    // ==================================================================== //

    private boolean isL3(String riskLevel) {
        return "L3".equalsIgnoreCase(nvl(riskLevel).trim());
    }

    private String applyKeyword(String categoryKey) {
        return "APPLY " + categoryKey;
    }

    private int managedItemCount(List<OpsServerConfigItem> rows) {
        int n = 0;
        for (OpsServerConfigItem r : rows) {
            if (trimToNull(r.getItemValue()) != null) {
                n++;
            }
        }
        return n;
    }

    private String guessType(String categoryKey, String itemKey) {
        if ("sysctl".equals(categoryKey)) {
            String k = nvl(itemKey);
            if (k.startsWith("net.ipv4.conf") || k.endsWith("syncookies") || k.endsWith("accept_source_route")) {
                return "bool";
            }
            return "int";
        }
        return "string";
    }

    private String serializeItems(List<OpsServerConfigItem> rows) {
        StringBuilder sb = new StringBuilder();
        for (OpsServerConfigItem r : rows) {
            sb.append(r.getSort() == null ? 0 : r.getSort()).append('\t')
                    .append(nvl(r.getItemKey())).append('\t')
                    .append(nvl(r.getItemValue())).append('\n');
        }
        return sb.toString();
    }

    private List<OpsServerConfigItem> deserializeItems(String text) {
        List<OpsServerConfigItem> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\t", 3);
            if (p.length < 2) {
                continue;
            }
            OpsServerConfigItem it = new OpsServerConfigItem();
            try {
                it.setSort(Integer.valueOf(p[0].trim()));
            } catch (NumberFormatException e) {
                it.setSort(0);
            }
            it.setItemKey(p[1]);
            it.setItemValue(p.length > 2 ? trimToNull(p[2]) : null);
            out.add(it);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object node) {
        if (node instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private Map<String, String> asStringMap(Object node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** 宿主各阶段明细 → 可读文本 */
    private String flatten(Map<String, Object> node) {
        StringBuilder sb = new StringBuilder();
        flattenTo(node, "", sb, 0);
        return sb.toString();
    }

    private void flattenTo(Object node, String prefix, StringBuilder sb, int depth) {
        if (depth > 6) {
            return;
        }
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                Object v = e.getValue();
                if (v instanceof Map<?, ?>) {
                    flattenTo(v, key, sb, depth + 1);
                } else if (v instanceof List<?> list) {
                    sb.append(key).append(" = ").append(list).append('\n');
                } else {
                    sb.append(key).append(" = ").append(v == null ? "-" : String.valueOf(v)).append('\n');
                }
            }
        } else {
            sb.append(prefix).append(" = ").append(node == null ? "-" : String.valueOf(node)).append('\n');
        }
    }

    private String stageSummary(Map<String, Object> stages) {
        if (stages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : stages.entrySet()) {
            Object v = e.getValue();
            String brief;
            if (v instanceof Map<?, ?> m) {
                Object out = m.get("output") != null ? m.get("output")
                        : (m.get("ok") != null ? m.get("ok") : m.get("error"));
                brief = out == null ? String.valueOf(m) : String.valueOf(out);
            } else {
                brief = v == null ? "-" : String.valueOf(v);
            }
            sb.append(e.getKey()).append(": ").append(brief).append('\n');
        }
        return sb.toString();
    }

    private String stageError(Map<String, Object> stages) {
        for (String k : List.of("preview", "verify", "apply")) {
            Object s = stages.get(k);
            if (s instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("ok"))) {
                Object out = m.get("output") != null ? m.get("output") : m.get("error");
                return out == null ? k + " 阶段失败" : String.valueOf(out);
            }
        }
        return null;
    }

    private String currentOperator() {
        try {
            return LoginHelper.isLogin() ? LoginHelper.getUsername() : "system";
        } catch (RuntimeException e) {
            return "system";
        }
    }

    private String currentIp() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest request = sra.getRequest();
                String xff = request.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) {
                    return xff.split(",")[0].trim();
                }
                String real = request.getHeader("X-Real-IP");
                if (real != null && !real.isBlank()) {
                    return real.trim();
                }
                return request.getRemoteAddr();
            }
        } catch (RuntimeException e) {
            log.debug("获取客户端 IP 失败：{}", e.getMessage());
        }
        return null;
    }

    private int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean hnas(String s) {
        return s != null && !s.isBlank();
    }

    private static int intOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String cut(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private ServiceException invalid(String message) {
        return new ServiceException(ErrorCode.SERVER_CONFIG_ITEM_INVALID.getCode(), message);
    }
}
