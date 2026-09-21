package com.serverpanel.ops.dto;

import java.util.List;

import lombok.Data;

/**
 * 服务详情（结构化 + 原文双视图）。
 *
 * <p>设计取向：结构化字段供「概览」页签快速判读，原文（status / cat）供排障时
 * 直接对照 systemd 原始语义，两者同时给到，避免面板把关键信息「翻译」丢失。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceDetailVO {

    /** 列表行的同一份结构 */
    private ServiceVO basic;

    private Integer mainPid;

    private Long memoryBytes;

    /** CPU 累计纳秒 */
    private Long cpuNanos;

    private Long uptimeSeconds;

    /** systemd 给出的进入 active 的时刻原文 */
    private String activeEnterTimestamp;

    private Integer restartCount;

    private String fragmentPath;

    private String execStart;

    /** 该单元的全部名称（规范名 + 别名） */
    private List<String> names;

    private List<String> wantedBy;

    private List<String> requiredBy;

    private List<String> requires;

    private List<String> after;

    /** 正向依赖（本单元依赖谁） */
    private List<String> dependencies;

    /** 反向依赖（谁依赖本单元）——L3 操作的影响面提示数据来源 */
    private List<String> dependents;

    /** systemctl status 原文 */
    private String rawStatus;

    /** systemctl cat 原文（Unit 文件） */
    private String unitFile;

    private boolean protectedService;

    /** 命中断言清单时的提示文案 */
    private String protectionHint;
}
