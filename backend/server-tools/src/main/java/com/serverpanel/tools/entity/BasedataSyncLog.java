package com.serverpanel.tools.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import lombok.Data;

/**
 * 基础数据同步日志 sys_basedata_sync_log（只增表，不继承 BaseEntity）。
 *
 * <p>该表没有 created_at/updated_at 列，继承 BaseEntity 会让 MetaObjectHandler
 * 往不存在的列填值；故自行声明主键（应用侧雪花）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Data
@TableName("sys_basedata_sync_log")
public class BasedataSyncLog {

    /**
     * 主键序列化为字符串：雪花 ID 超出 JS 安全整数范围（与其它模块口径一致）。
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 数据类型：region/phone/bin */
    private String dataType;

    /** 1手动 2定时 */
    private Integer triggerType;

    /** 0进行中 1成功 2失败 */
    private Integer status;

    /** 源数据总行数 */
    private Integer rowsTotal;

    /** 新增行数 */
    private Integer rowsInserted;

    /** 更新行数 */
    private Integer rowsUpdated;

    /** 软删（置0）行数 */
    private Integer rowsDisabled;

    /** 结果摘要/失败原因 */
    private String message;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}
