package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ID 方案参数定义。
 *
 * <p>与 {@link FieldVO} 同思路（前端按服务端下发的定义渲染表单，避免「后端加参数、
 * 前端忘记加输入框」），但多了 {@code switch} 与 {@code select} 两种控件类型，
 * 且数值边界用 {@code Long} —— 时间基准（epoch）本身就是一个 13 位毫秒数，
 * 超出 int 范围。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdParamVO {

    /** 参数名（提交时作为 params 的 key） */
    private String name;

    /** 标签 */
    private String label;

    /** 控件类型：number / text / switch / select */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 默认值（统一字符串化，前端原样回填） */
    private String def;

    /** 帮助文案 */
    private String help;

    /** number 最小值（可为 null） */
    private Long min;

    /** number 最大值（可为 null） */
    private Long max;

    /** select 的候选值（其他类型为 null） */
    private List<OptionVO> options;
}
