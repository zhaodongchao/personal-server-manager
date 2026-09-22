package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表单字段描述。
 *
 * <p>界面按服务端下发的字段定义渲染，避免「后端新增参数、前端忘记加输入框」。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldVO {

    /** 参数名（提交时作为 params 的 key） */
    private String name;

    /** 标签 */
    private String label;

    /** text / number / textarea */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 默认值（字符串） */
    private String def;

    /** 帮助文案 */
    private String help;

    /** number 最小值（可为 null） */
    private Integer min;

    /** number 最大值（可为 null） */
    private Integer max;
}
