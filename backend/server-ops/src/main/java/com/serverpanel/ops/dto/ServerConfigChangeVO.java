package com.serverpanel.ops.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 服务器配置变更历史视图对象。
 *
 * <p>{@link #detail} 为 true 时才回填全文字段（前后内容 / diff / 输出 / 配置项快照），
 * 列表分页只带摘要，避免把大字段整页塞给前端。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigChangeVO {

    /** 变更记录 ID */
    private String id;

    /** 类别键 */
    private String categoryKey;

    /** 类别名（便于列表直接展示） */
    private String categoryName;

    /** 操作类型：APPLY / RESTORE */
    private String op;

    /** 结果：SUCCESS / FAILED / ROLLED_BACK */
    private String result;

    /** 失败原因 */
    private String errorMsg;

    /** 操作人 */
    private String operator;

    /** 操作人 IP */
    private String operatorIp;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 宿主备份文件路径 */
    private String backupPath;

    /** 时间 */
    private LocalDateTime createdAt;

    /** 是否包含详情字段 */
    private Boolean detail;

    // ===== 以下字段仅在 detail=true 时返回 =====

    /** 变更前托管文件全文 */
    private String beforeContent;

    /** 变更后托管文件全文 */
    private String afterContent;

    /** 行级 diff */
    private String diff;

    /** 校验/预演输出 */
    private String validateOutput;

    /** 生效输出 */
    private String applyOutput;

    /** 宿主各阶段明细文本 */
    private String stagesText;

    /** 变更前配置项快照 */
    private List<ServerConfigItemVO> beforeItems;

    /** 变更后配置项快照 */
    private List<ServerConfigItemVO> afterItems;
}
