package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * 可执行命令白名单（供表单下拉/提示，避免用户瞎猜）。
 *
 * <p>命令实际在<b>宿主机</b>上执行（容器内没有这些系统命令），
 * 因此这里同时给出「后端允许」与「宿主机上真实存在」两部分，
 * 前端据此把「允许但宿主机缺失」的命令标注为不可用。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class CronWhitelistVO {

    /** 后端白名单（内置 + 配置扩展），已排序 */
    private List<String> allowed;

    /** 白名单中宿主机上确实存在的命令 */
    private List<String> available;

    /** 白名单中宿主机上缺失的命令（命令存在但通道不可用时会执行失败） */
    private List<String> missing;

    /** 宿主通道是否可用；false 时任何命令都无法执行 */
    private boolean hostAvailable;

    private String hostMessage;
}
