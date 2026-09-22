package com.serverpanel.ops.dto;

import java.util.Map;

import lombok.Data;

/**
 * 「一键生效 / 一键恢复」执行结果。
 *
 * <p>{@link #stages} 为宿主代理返回的分阶段明细，键固定为
 * {@code preview / backup / write / verify / apply / rollback / effective}，
 * 与「9 道闸门」一一对应，失败时可在页面上逐段回放定位问题。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigApplyVO {

    /** 类别键 */
    private String categoryKey;

    /** 操作类型：APPLY / RESTORE */
    private String op;

    /** 是否已生效 */
    private Boolean applied;

    /** 失败时是否已自动回滚 */
    private Boolean rolledBack;

    /** 面向用户的结论 */
    private String message;

    /** 变更历史记录 ID（可据此查看详情或一键恢复） */
    private String changeId;

    /** 宿主备份文件绝对路径 */
    private String backupPath;

    /** 预演校验输出 */
    private String validateOutput;

    /** 生效阶段输出 */
    private String applyOutput;

    /** 行级 diff */
    private String diff;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 宿主各阶段结构化明细 */
    private Map<String, Object> stages;

    /** 生效后回读到的系统生效值（sysctl 键值对 / sshd 有效项） */
    private Map<String, String> effective;
}
