package com.serverpanel.ops.dto;

import java.util.List;

import lombok.Data;

/**
 * 服务器配置项新增/编辑请求体。
 *
 * <p>仅承载「可由用户在页面上编辑」的字段。itemValue 允许为空——语义是
 * <b>不托管该项</b>（渲染时整行不写入），因此把已有项的值清空即等于「交还给系统默认」。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigItemBody {

    /** 参数名；编辑时以路径参数为准，此字段仅新增时必填 */
    private String itemKey;

    /** 托管值；空字符串 / null 表示不托管该项 */
    private String itemValue;

    /** 值类型：int / bool / enum / string / text */
    private String valueType;

    /** 枚举选项（valueType=enum） */
    private List<String> options;

    /** 推荐值（提示用） */
    private String recommended;

    /** 说明 */
    private String description;

    /** 排序；为空时由服务端分配 */
    private Integer sort;
}
