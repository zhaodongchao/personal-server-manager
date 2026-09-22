/**
 * 定时任务日志 API（应用栈 / 定时任务日志）。
 *
 * <p>日志表沿用 xxl-job 的<b>双段式</b>结构：trigger_* 段记录「调度是否派发出去」，
 * handle_* 段记录「执行结果如何」。两段分开的价值在于 —— 派发失败（trigger_code=500）
 * 与执行失败（handle_code=500）是两类完全不同的故障，一张表里必须能分辨，
 * 否则「任务没跑」和「任务跑了但失败了」会混成一个状态。
 */
import { requestClient } from '#/api/request';

export namespace JobLogApi {
  /** 调度日志 */
  export interface JobLog {
    id: string;
    jobId?: string;
    /** 任务名快照：任务被删后日志仍可读 */
    jobName?: string;
    handler?: string;
    executorAddress?: string;
    executorAppName?: string;
    /** CRON | MANUAL */
    triggerType?: string;
    /** 0 派发成功 / 404 任务不存在 / 500 派发失败 */
    triggerCode?: number;
    triggerMsg?: string;
    triggerTime?: string;
    /** 0 执行成功 / 500 执行失败 */
    handleCode?: number;
    handleMsg?: string;
    handleTime?: string;
    handleDurationMs?: number;
    /** RUNNING | SUCCESS | FAILED | TIMEOUT | DISCARDED | KILLED */
    status?: string;
    /** 重试轮次，0 为首次 */
    retryIndex?: number;
    executorOutput?: string;
    createdAt?: string;
  }

  /** 列表筛选 */
  export interface JobLogQuery {
    jobId?: string;
    jobName?: string;
    status?: string;
    handler?: string;
    triggerType?: string;
    beginTime?: string;
    endTime?: string;
    pageNum?: number;
    pageSize?: number;
  }

  /** 统计 */
  export interface Statistics {
    total: number;
    success: number;
    failed: number;
    running: number;
    discarded: number;
    killed: number;
    avgDurationMs: number;
    retentionDays: number;
  }

  /** 清理入参 */
  export interface ClearBody {
    jobId?: string;
    beforeTime?: string;
    status?: string;
    /** 危险操作确认关键字 */
    confirm?: string;
  }
}

/** 分页查询日志 */
export async function getJobLogPageApi(params: JobLogApi.JobLogQuery) {
  return requestClient.get<{
    records: JobLogApi.JobLog[];
    total: number;
  }>('/appstack/job-log/page', { params });
}

/** 日志统计 */
export async function getJobLogStatisticsApi() {
  return requestClient.get<JobLogApi.Statistics>('/appstack/job-log/statistics');
}

/** 日志保留天数 */
export async function getJobLogRetentionApi() {
  return requestClient.get<{ retentionDays: number }>(
    '/appstack/job-log/retention',
  );
}

/** 日志详情 */
export async function getJobLogDetailApi(id: string) {
  return requestClient.get<JobLogApi.JobLog>(`/appstack/job-log/${id}`);
}

/** 执行输出（executor_output 全文） */
export async function getJobLogOutputApi(id: string) {
  return requestClient.get<string>(`/appstack/job-log/${id}/output`);
}

/** 清理日志（危险，需 confirm） */
export async function clearJobLogApi(body: JobLogApi.ClearBody) {
  return requestClient.request<{ deleted: number }>('/appstack/job-log/clear', {
    method: 'DELETE',
    data: body,
  });
}
