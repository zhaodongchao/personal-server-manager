package com.serverpanel.ops.entity.mongo;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * 服务器配置「变更历史」文档（MongoDB，集合 {@code ops_server_config_change}）。
 *
 * <p>本模块最关键的留痕载体：每次「一键生效 / 一键恢复」都会在<b>生效动作之前</b>先落一条
 * 记录，内容包括变更前后<b>完整文件内容</b>、行级 diff、校验输出、生效输出、宿主各阶段结果、
 * 操作人与 IP、耗时、结果。<b>任一条记录都可作为恢复源</b>：
 * <ul>
 *   <li>{@code beforeItems} —— 该次变更<b>前</b>的配置项快照 → 「回到这次变更之前」</li>
 *   <li>{@code afterItems} —— 该次变更<b>后</b>的配置项快照 → 「回到某次生效之后」</li>
 * </ul>
 * 快照刻意存为<b>可读文本</b>（每行 {@code sort \t itemKey \t itemValue}）而非 JSON：
 * 既免去对 Jackson 主版本（本项目为 Jackson 3 / tools.jackson）的编译期依赖，
 * 也让运维人员能直接读懂历史文档。
 *
 * <p>注意：即使生效失败（含已自动回滚）也会落记录，{@link #result} 记为 FAILED / ROLLED_BACK，
 * 保证「危险操作一定有痕」。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@Document("ops_server_config_change")
public class OpsServerConfigChange {

    /** 文档 ID（ObjectId 十六进制字符串） */
    @Id
    private String id;

    /** 类别键 */
    @Indexed
    private String categoryKey;

    /** 操作类型：APPLY（一键生效）/ RESTORE（按历史一键恢复） */
    private String op;

    /** 变更前托管文件全文（首次托管时为 null） */
    private String beforeContent;

    /** 变更后托管文件全文（渲染结果） */
    private String afterContent;

    /** 行级 diff（{@code - } 删除 / {@code + } 新增 / 两空格 未变） */
    private String diff;

    /** 校验/预演输出 */
    private String validateOutput;

    /** 生效输出（宿主各阶段的合并文本） */
    private String applyOutput;

    /** 宿主各阶段结构化结果的文本展开（preview/backup/write/verify/apply/effective） */
    private String stagesText;

    /** 变更前配置项快照文本（恢复依据，格式：sort \t itemKey \t itemValue） */
    private String beforeItems;

    /** 变更后配置项快照文本（恢复依据） */
    private String afterItems;

    /** 宿主备份文件绝对路径（可据此定位 .psm.bak） */
    private String backupPath;

    /** 耗时（毫秒） */
    private long durationMs;

    /** 结果：SUCCESS / FAILED / ROLLED_BACK */
    private String result;

    /** 失败原因（截断保存） */
    private String errorMsg;

    /** 操作人登录名 */
    private String operator;

    /** 操作人 IP */
    private String operatorIp;

    private LocalDateTime createdAt;
}
