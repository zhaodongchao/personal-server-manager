package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 正则模板新增/编辑请求体。
 *
 * <p>pattern 长度上限与表列 VARCHAR(2000) 对齐；flags 只允许 imux 组合
 * （白名单校验在 Service 里做更细致的「不重复」检查，这里先挡明显非法值）。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class RegexTemplateBody {

    /** 模板名称（全局唯一） */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称不能超过 100 字")
    private String name;

    /** 正则表达式 */
    @NotBlank(message = "正则表达式不能为空")
    @Size(max = 2000, message = "正则表达式不能超过 2000 字符")
    private String pattern;

    /** 标志组合（imux 的子集），空串表示无标志 */
    @Pattern(regexp = "[imux]{0,4}", message = "正则标志不合法（仅允许 i m s x u）")
    @Size(max = 10, message = "正则标志过长")
    private String flags;

    /** 分类 */
    @Size(max = 50, message = "分类不能超过 50 字")
    private String category;

    /** 用途说明 */
    @Size(max = 500, message = "用途说明不能超过 500 字")
    private String description;

    /** 示例文本提示 */
    @Size(max = 500, message = "示例文本不能超过 500 字")
    private String sample;

    /** 排序（小在前） */
    private Integer sort;
}
