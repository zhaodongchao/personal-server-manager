package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下拉选项。
 *
 * <p>用于把「算法/编码方式的取值与中文展示名」放在服务端统一定义，
 * 前端不再硬编码算法清单（与定时任务模块的 schema 驱动保持一致）。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OptionVO {

    /** 传给后端的取值 */
    private String value;

    /** 界面展示名 */
    private String label;
}
