package com.serverpanel.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.serverpanel.ops.entity.OpsCronLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 计划任务日志 Mapper。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
public interface OpsCronLogMapper extends BaseMapper<OpsCronLog> {

    /**
     * 每个任务只保留最近 {@code keep} 条日志，其余删除。
     *
     * <p>MySQL 不允许在 {@code IN (...)} 子查询里直接写 {@code LIMIT}，
     * 故用派生表（derived table）包一层——这是 MySQL 上做「保留最新 N 条」的标准写法。
     *
     * @param jobId 任务 ID
     * @param keep  保留条数
     * @return 删除行数
     */
    @Delete("DELETE FROM ops_cron_log WHERE job_id = #{jobId} AND id NOT IN ("
            + "SELECT id FROM (SELECT id FROM ops_cron_log WHERE job_id = #{jobId} "
            + "ORDER BY started_at DESC, id DESC LIMIT #{keep}) t)")
    int keepLatest(@Param("jobId") Long jobId, @Param("keep") int keep);
}
