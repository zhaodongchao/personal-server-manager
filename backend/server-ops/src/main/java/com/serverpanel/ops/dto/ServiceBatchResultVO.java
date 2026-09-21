package com.serverpanel.ops.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 批量服务操作结果汇总。
 *
 * <p>逐个执行、逐个记录，**不因单个失败而中止**（前端逐条展示结果）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceBatchResultVO {

    private int total;

    private int success;

    private int failed;

    private List<Item> items = new ArrayList<>();

    /** 是否需要 L3 二次确认（前端据此弹出「键入关键字」对话框） */
    private boolean confirmRequired;

    /** 二次确认需键入的字符串（批量操作为 CONFIRM） */
    private String confirmKeyword;

    @Data
    public static class Item {

        private String name;

        private boolean ok;

        private String message;

        /** 执行后回读状态 */
        private String activeAfter;
    }
}
