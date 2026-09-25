package com.serverpanel.tools.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 基础数据同步状态（GET /system/basedata/sync/status）。
 *
 * @param types 各类型最近一次同步状态 + 当前行数
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public record SyncStatusVO(List<TypeStatus> types) {

    /**
     * 单类型状态。
     *
     * @param dataType   region/phone/bin
     * @param rows       当前行数（region 为启用行数）
     * @param ready      是否已同步过（region 未同步时前端黄底提示初始化）
     * @param running    当前是否正在同步
     * @param lastSyncAt 最近一次同步完成时间
     * @param lastStatus 最近一次结果：1成功 2失败（null=从未同步）
     * @param lastMessage 最近一次结果摘要
     */
    public record TypeStatus(
            String dataType,
            long rows,
            boolean ready,
            boolean running,
            LocalDateTime lastSyncAt,
            Integer lastStatus,
            String lastMessage) {
    }
}
