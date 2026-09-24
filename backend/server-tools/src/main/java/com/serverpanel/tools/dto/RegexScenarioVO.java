package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 正则生成场景（schema 驱动，与二维码工具的内容类型同构）。
 *
 * <p>前端按 {@code fields} 动态渲染表单，提交时以 {@code params[name]=value}
 * 回传，后端新增参数无需改前端。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegexScenarioVO {

    /** 场景标识（generate 请求的 scenario） */
    private String value;

    /** 界面展示名 */
    private String label;

    /** 场景说明 */
    private String desc;

    /** 该场景的动态字段定义 */
    private List<FieldVO> fields;
}
