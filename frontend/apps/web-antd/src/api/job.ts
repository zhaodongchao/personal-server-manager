/**
 * 定时任务管理 API（应用栈 / 定时任务管理）。
 *
 * <p>设计参照 xxl-job 的「调度中心 / 执行器」分层：任务（job）是调度单元，执行器
 * （executor）是执行单元。内置执行器 serverpanel-builtin 在面板进程内执行，外部
 * 执行器通过 HTTP 接入 —— 本版本只把「执行器」这个维度先建模出来，不做注册中心
 * 与心跳（借形不借体）。
 *
 * <p>注意 handlerParam 的服务端形态是 <b>JSON 字符串</b>（表里是 varchar/text），
 * 表单层需要自行 parse / stringify。
 */
import { requestClient } from '#/api/request';

export namespace JobApi {
  /** 执行器 */
  export interface Executor {
    id: string;
    appName: string;
    executorName?: string;
    /** BUILTIN | HTTP */
    type: string;
    baseUrl?: string;
    /** AVAILABLE | UNREACHABLE | DISABLED */
    status: string;
    failStreak?: number;
    lastBeatAt?: string;
    lastError?: string;
    remark?: string;
    /** 是否已配置令牌（令牌本身不回传） */
    tokenSet?: boolean;
    authTokenMasked?: string;
    createdAt?: string;
  }

  /** 任务 */
  export interface Job {
    id: string;
    jobName: string;
    jobDesc?: string;
    executorId: string;
    /** SHELL | HTTP | SERVICE | INTERNAL */
    handler: string;
    /** handler 参数，JSON 字符串 */
    handlerParam?: string;
    cronExpr: string;
    /** FIRST | ROUND | RANDOM | FAILOVER */
    routeStrategy: string;
    /** SERIAL | DISCARD_LATER | COVER_EARLY */
    blockStrategy: string;
    timeoutSec?: number;
    retryCount?: number;
    /** 0 停用 / 1 启用 */
    status?: number;
    confirmKeyword?: string;
    failStreak?: number;
    lastStatus?: string;
    lastFireTime?: string;
    nextFireTime?: string;
    owner?: string;
    createdAt?: string;
    updatedAt?: string;
  }

  /** 新建 / 编辑任务入参 */
  export interface JobBody {
    jobName: string;
    jobDesc?: string;
    executorId: number | string;
    handler: string;
    handlerParam?: Record<string, any>;
    cronExpr: string;
    routeStrategy?: string;
    blockStrategy?: string;
    timeoutSec?: number;
    retryCount?: number;
    status?: number;
    confirmKeyword?: string;
  }

  /** 列表筛选 */
  export interface JobQuery {
    keyword?: string;
    handler?: string;
    executorId?: number | string;
    status?: number;
    lastStatus?: string;
    pageNum?: number;
    pageSize?: number;
  }

  /** 处理器表单字段（服务端下发，前端按此渲染） */
  export interface HandlerField {
    name: string;
    label: string;
    /** text | number | textarea | select */
    type: string;
    required: boolean;
    placeholder?: string;
    options?: string[];
    help?: string;
  }

  /** 处理器骨架 */
  export interface HandlerSchema {
    type: string;
    label: string;
    description: string;
    fields: HandlerField[];
  }

  /** 白名单命令（available 表示宿主侧是否真实存在该程序） */
  export interface CommandItem {
    name: string;
    available: boolean;
    path?: string;
  }

  /** 内置任务 */
  export interface InternalTaskOption {
    code: string;
    label: string;
    description: string;
    /**
     * 任务自己声明的专用字段（服务端下发）。
     *
     * <p>为空时界面回落到「参数（JSON 对象）」文本域；非空则按字段渲染结构化表单。
     */
    fields?: HandlerField[];
    /**
     * 是否接受自由 JSON 参数；false 表示该任务无参数，界面不渲染参数输入框。
     */
    freeFormParams?: boolean;
  }

  /** 枚举字典与各类上限 */
  export interface Options {
    handlers: string[];
    routeStrategies: string[];
    blockStrategies: string[];
    statuses: string[];
    executorTypes: string[];
    executorStatuses: string[];
    destructiveServiceActions: string[];
    protectedUnits: string[];
    internalTasks: InternalTaskOption[];
    minIntervalSeconds: number;
    maxTimeoutSeconds: number;
    maxRetryCount: number;
    logRetentionDays: number;
  }

  /** 列表页顶部统计 */
  export interface Stats {
    total: number;
    enabled: number;
    failing: number;
    todayRuns: number;
    running: number;
    /** 调度器总开关（配置 serverpanel.job.scheduler.enabled） */
    schedulerEnabled: boolean;
    /** 宿主执行通道是否可用（SHELL / SERVICE 依赖它） */
    hostChannelAvailable: boolean;
  }

  /** cron 校验与预览 */
  export interface CronPreview {
    valid: boolean;
    message?: string;
    nextTimes: string[];
    intervalSeconds?: number;
    minIntervalSeconds?: number;
  }

  /** 执行器新建 / 编辑入参 */
  export interface ExecutorBody {
    appName: string;
    executorName?: string;
    type: string;
    baseUrl?: string;
    authToken?: string;
    status?: string;
    remark?: string;
  }
}

// ==================== 任务 ====================

/** 分页查询任务 */
export async function getJobPageApi(params: JobApi.JobQuery) {
  return requestClient.get<{
    records: JobApi.Job[];
    total: number;
  }>('/appstack/job/page', { params });
}

/** 列表页统计 */
export async function getJobStatsApi() {
  return requestClient.get<JobApi.Stats>('/appstack/job/stats');
}

/** 4 类处理器的表单 schema */
export async function getJobHandlersApi() {
  return requestClient.get<JobApi.HandlerSchema[]>('/appstack/job/handlers');
}

/** 白名单命令（SHELL 用） */
export async function getJobCommandsApi() {
  return requestClient.get<JobApi.CommandItem[]>('/appstack/job/commands');
}

/** 枚举字典 */
export async function getJobOptionsApi() {
  return requestClient.get<JobApi.Options>('/appstack/job/options');
}

/** cron 校验 + 未来 5 次预览 */
export async function validateJobCronApi(cronExpr: string) {
  return requestClient.post<JobApi.CronPreview>('/appstack/job/cron/validate', {
    cronExpr,
  });
}

/** 任务详情 */
export async function getJobDetailApi(id: string) {
  return requestClient.get<JobApi.Job>(`/appstack/job/${id}`);
}

/** 某任务未来 n 次执行时间 */
export async function getJobNextTimesApi(id: string, n = 5) {
  return requestClient.get<string[]>(`/appstack/job/${id}/next-times`, {
    params: { n },
  });
}

/** 新建任务 */
export async function createJobApi(body: JobApi.JobBody) {
  return requestClient.post<JobApi.Job>('/appstack/job', body);
}

/** 编辑任务 */
export async function updateJobApi(id: string, body: JobApi.JobBody) {
  return requestClient.put<JobApi.Job>(`/appstack/job/${id}`, body);
}

/** 删除任务（危险，confirm 需为 `DELETE JOB <任务名>`） */
export async function deleteJobApi(id: string, confirm?: string) {
  return requestClient.request<void>(`/appstack/job/${id}`, {
    method: 'DELETE',
    data: { confirm },
  });
}

/** 复制任务 */
export async function copyJobApi(id: string) {
  return requestClient.post<JobApi.Job>(`/appstack/job/${id}/copy`);
}

/** 启用 */
export async function enableJobApi(id: string) {
  return requestClient.post<JobApi.Job>(`/appstack/job/${id}/enable`);
}

/** 停用 */
export async function disableJobApi(id: string) {
  return requestClient.post<JobApi.Job>(`/appstack/job/${id}/disable`);
}

/** 立即执行一次（paramOverride 可临时覆盖任务参数） */
export async function runJobApi(
  id: string,
  paramOverride?: Record<string, any>,
) {
  return requestClient.post<{ logId: string; message: string }>(
    `/appstack/job/${id}/run`,
    { paramOverride },
  );
}

/** 停止正在执行的实例（危险，confirm 需为 `STOP JOB <任务名>`） */
export async function stopJobApi(id: string, confirm?: string) {
  return requestClient.post<{
    stopped: boolean;
    logId?: string;
    terminated?: boolean;
    message: string;
  }>(`/appstack/job/${id}/stop`, { confirm });
}

// ==================== 执行器 ====================

/** 执行器列表 */
export async function getExecutorListApi() {
  return requestClient.get<JobApi.Executor[]>('/appstack/job/executor/list');
}

/** 新建执行器 */
export async function createExecutorApi(body: JobApi.ExecutorBody) {
  return requestClient.post<JobApi.Executor>('/appstack/job/executor', body);
}

/** 编辑执行器 */
export async function updateExecutorApi(
  id: string,
  body: JobApi.ExecutorBody,
) {
  return requestClient.put<JobApi.Executor>(`/appstack/job/executor/${id}`, body);
}

/** 删除执行器（危险，confirm 需为 `DELETE EXECUTOR <appName>`） */
export async function deleteExecutorApi(id: string, confirm?: string) {
  return requestClient.request<void>(`/appstack/job/executor/${id}`, {
    method: 'DELETE',
    data: { confirm },
  });
}

/** 连通性探测 */
export async function testExecutorApi(id: string) {
  return requestClient.post<JobApi.Executor>(`/appstack/job/executor/${id}/test`);
}
