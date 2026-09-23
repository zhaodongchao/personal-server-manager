package com.serverpanel.appstack.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 取号数据源的环境状态。
 *
 * <p>页面加载时先拿它，就能提前把「密钥未配置，无法保存」「允许的库只有 psm_tools」
 * 这类前提条件摆出来 —— 而不是等用户填完整张表单再告诉他保存不了。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@AllArgsConstructor
public class IdSourceStatusVO {

    /** 口令加密密钥是否可用（不可用则无法新增/修改数据源） */
    private boolean cipherReady;

    /** 允许作为取号目标的库（硬黑名单之外的放行名单） */
    private List<String> allowedDatabases;

    /** 序列方案的默认对象名 */
    private String defaultSequence;

    /** 自增方案的默认对象名 */
    private String defaultTable;

    /** 自动创建对象必须带的前缀 */
    private String autoPrefix;
}
