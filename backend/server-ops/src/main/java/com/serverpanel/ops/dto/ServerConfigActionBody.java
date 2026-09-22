package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 服务器配置「一键生效 / 一键恢复」的请求体。
 *
 * <p>{@link #confirm} 用于 L3 高风险类别（sshd）的二次确认：必须逐字键入
 * {@code APPLY sshd} 这类关键字，与服务端预置关键字完全一致才放行——这是「自锁护栏」，
 * 目的是让「改 SSH 端口 / 关密码登录」这类操作不可能被误点。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigActionBody {

    /** 二次确认关键字（L3 类别必填） */
    private String confirm;

    /** 恢复目标：{@code before}（回到该次变更之前，默认）/ {@code after}（回到该次变更之后） */
    private String target;
}
