package com.serverpanel.ops.dto;

import java.util.List;

import lombok.Data;

/**
 * 服务器配置项视图对象：托管值 + 当前系统生效值 + 推荐值三列对比。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigItemVO {

    /** 文档 ID */
    private String id;

    /** 所属类别键 */
    private String categoryKey;

    /** 参数名 */
    private String itemKey;

    /** 当前托管值；为空表示不托管该项 */
    private String itemValue;

    /** 当前系统真实生效值（宿主代理 {@code sys.probe} 读取；读不到为 null） */
    private String effectiveValue;

    /** 系统默认值（探测回填，只读参考） */
    private String defaultValue;

    /** 值类型 */
    private String valueType;

    /** 枚举选项 */
    private List<String> options;

    /** 推荐值 */
    private String recommended;

    /** 说明 */
    private String description;

    /** 排序 */
    private Integer sort;

    /** 1 系统预置 0 用户新增 */
    private Integer builtin;
}
