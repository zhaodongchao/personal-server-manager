package com.serverpanel.ops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 计划任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_cron_job")
public class OpsCronJob extends BaseEntity {

    private String name;

    /** 5 段 unix cron 表达式 */
    private String cronExpr;

    /** 命令（空格分隔的 argv，首项须在白名单内） */
    private String command;

    private Integer timeoutSec;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;

    private LocalDateTime lastRunAt;

    /** 调度器计算的下次执行时间 */
    private LocalDateTime nextRunAt;
}
