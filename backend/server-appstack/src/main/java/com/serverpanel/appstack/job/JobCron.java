package com.serverpanel.appstack.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

/**
 * cron 解析与校验工具（Spring 原生 {@link CronExpression}，6 段：秒 分 时 日 月 周）。
 *
 * <p><b>为什么不用 Quartz / cron-utils</b>：xxl-job 自身也是自研调度线程池 + cron 解析，
 * 借鉴它的思想并不需要 Quartz；而 cron-utils 刚随上一轮模块下线从依赖里删除，
 * 为本次重新引入等于自打嘴巴。Spring 的语义与项目既有 {@code @Scheduled(cron = "0 30 3 * * ?")}
 * 完全一致，前端提示不需要另教一套（设计 ADR-3）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public final class JobCron {

    /** 预览与查询的条数上限 */
    public static final int MAX_LOOKAHEAD = 20;

    private JobCron() {}

    /** 解析校验；非法一律抛 6031 */
    public static CronExpression parse(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new ServiceException(ErrorCode.JOB_CRON_INVALID.getCode(), "cron 表达式不能为空");
        }
        try {
            return CronExpression.parse(expr.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.JOB_CRON_INVALID.getCode(),
                    "cron 表达式非法（需 6 段：秒 分 时 日 月 周）—— " + e.getMessage());
        }
    }

    /** 未来 n 次触发时间 */
    public static List<LocalDateTime> nextTimes(String expr, int n) {
        CronExpression cron = parse(expr);
        int count = Math.min(Math.max(n, 1), MAX_LOOKAHEAD);
        List<LocalDateTime> times = new ArrayList<>(count);
        LocalDateTime cursor = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            LocalDateTime next = cron.next(cursor);
            if (next == null) {
                break;
            }
            times.add(next);
            cursor = next;
        }
        return times;
    }

    /** 下一次触发时间（调度器回写列表用） */
    public static LocalDateTime nextFireTime(String expr) {
        return parse(expr).next(LocalDateTime.now());
    }

    /**
     * 最小间隔护栏：校验未来相邻若干次的间隔，任一小于 minSeconds 即拒绝。
     *
     * <p>面板是管理工具，不是流计算引擎。秒级 cron（每秒触发一次）会把宿主的
     * mysqldump 打成 DDoS，同时把调度线程与 MySQL 一起打满。
     */
    public static void validateMinInterval(String expr, int minSeconds) {
        CronExpression cron = parse(expr);
        LocalDateTime previous = cron.next(LocalDateTime.now());
        if (previous == null) {
            throw new ServiceException(ErrorCode.JOB_CRON_INVALID.getCode(),
                    "cron 表达式在未来不会有任何触发时间");
        }
        for (int i = 0; i < 3; i++) {
            LocalDateTime next = cron.next(previous);
            if (next == null) {
                return;
            }
            if (Duration.between(previous, next).getSeconds() < minSeconds) {
                throw new ServiceException(ErrorCode.JOB_CRON_INVALID.getCode(),
                        "触发间隔过短（两个相邻触发点相差 "
                                + Duration.between(previous, next).getSeconds()
                                + " 秒，最小 " + minSeconds + " 秒）");
            }
            previous = next;
        }
    }

    /** 转毫秒时间戳（外部执行器契约字段用） */
    public static long toEpochMilli(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
