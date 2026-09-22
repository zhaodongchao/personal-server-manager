package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 服务器配置类别视图对象：数据库中的类别元数据 + 宿主代理实时探测结果的合并视图。
 *
 * <p>可用性（{@link #available}）与托管文件路径（{@link #managedFile} / {@link #sourceFile}）
 * 一律以宿主代理 {@code sys.detect} 的返回为准；代理不可用时整页降级为只读并给出原因，
 * 不允许静默失败。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigCategoryVO {

    /** 类别键 */
    private String categoryKey;

    /** 类别名 */
    private String name;

    /** 说明 */
    private String description;

    /** ServerPanel 写入的托管文件路径（权威值来自宿主代理） */
    private String managedFile;

    /** 发行版主配置路径（仅 timesync 等多 provider 类别有值，供用户理解改动落在哪里） */
    private String sourceFile;

    /** provider（仅 timesync：chrony / timesyncd） */
    private String provider;

    /** 风险级 L1/L2/L3 */
    private String riskLevel;

    /** 生效说明 */
    private String applyHint;

    /** L3 类别生效需键入的关键字，如 {@code APPLY sshd}；非 L3 为 null */
    private String applyKeyword;

    /** 宿主能力是否可用 */
    private Boolean available;

    /** 不可用原因 */
    private String unavailableReason;

    /** 托管文件当前是否已存在（区分「从未生效过」与「已生效」） */
    private Boolean managed;

    /** 配置项总数 */
    private Long itemCount;

    /** 已托管（值非空）的配置项数 */
    private Long managedItemCount;

    /** 排序 */
    private Integer sort;
}
