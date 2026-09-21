import { requestClient } from '#/api/request';

export namespace OpsApi {
  // ==================== 宿主执行通道 ====================

  /**
   * 宿主执行通道能力快照。
   *
   * <p>运维三页（服务/计划/防火墙）依赖宿主侧真实系统能力（systemctl / journalctl /
   * ufw）。面板跑在容器里，容器内没有这些命令，必须经宿主代理执行。此对象即
   * 「通道是否可用 + 可用到什么程度」的唯一真源，页面进入前先查一次：
   * 不可用则整页降级为只读并展示安装指引，而不是静默返回空列表。
   */
  export interface HostCapability {
    /** 通道整体是否可用 */
    ok: boolean;
    /** none / hostagent / nsenter */
    mode: string;
    /** 协议版本，前端与代理都要求 ≥1 */
    protocol: number;
    agentVersion?: string;
    os?: string;
    kernel?: string;
    /** systemd 运行级别状态（degraded 表示有失败单元） */
    systemRunning?: string;
    /** ufw / firewalld / none */
    firewallBackend?: string;
    ufwAvailable?: boolean;
    /** 工具名 -> 解析到的可执行文件路径 */
    tools?: Record<string, string>;
    /** 缺失的必需工具 */
    missing?: string[];
    socketPath?: string;
    message?: string;
    /** 不可用时给用户的安装指引 */
    installHint?: string;
    checkedAt?: number;
  }

  // ==================== 进程 ====================

  /** 进程信息 */
  export interface ProcessInfo {
    pid: number;
    user: string;
    cpu: number;
    mem: number;
    stat: string;
    elapsed: string;
    cmd: string;
  }

  // ==================== systemd 服务 ====================

  /** systemd 服务（列表行，轻量属性集） */
  export interface ServiceVO {
    name: string;
    description?: string;
    /** loaded / not-found / masked */
    load?: string;
    /** active / inactive / failed / activating */
    active?: string;
    /** running / exited / dead / failed ... */
    sub?: string;
    /** enabled / disabled / static / masked / generated ... */
    unitFileState?: string;
    masked?: boolean;
    failed?: boolean;
    /** 是否为别名单元（如 dbus-org.freedesktop.xxx.service） */
    alias?: boolean;
    /** 别名指向的真实单元名 */
    aliasOf?: string;
    mainPid?: number;
    /** 常驻内存字节数 */
    memoryBytes?: number;
    /** 已运行秒数（由单调时钟换算，不解析本地化时间串） */
    uptimeSeconds?: number;
    restartCount?: number;
    fragmentPath?: string;
    stateChangeTimestamp?: string;
    /** 进入 failed 的时刻（epoch 毫秒） */
    failedSinceEpoch?: number;
    /** 处于 failed 状态的持续秒数 */
    failedSeconds?: number;
    /** 是否命中保护清单（承载面板/SSH 等关键路径） */
    protectedService?: boolean;
  }

  /** 服务总览统计 */
  export interface ServiceSummary {
    total: number;
    running: number;
    stopped: number;
    failed: number;
    enabled: number;
    disabled: number;
    masked: number;
    alias: number;
    hostChannelOk: boolean;
    systemRunning?: string;
  }

  /** 服务详情（结构化属性 + 依赖关系 + 原文） */
  export interface ServiceDetail {
    basic: ServiceVO;
    mainPid?: number;
    memoryBytes?: number;
    /** 累计 CPU 纳秒 */
    cpuNanos?: number;
    uptimeSeconds?: number;
    activeEnterTimestamp?: string;
    restartCount?: number;
    fragmentPath?: string;
    execStart?: string;
    /** 单元的全部名字（含别名） */
    names?: string[];
    wantedBy?: string[];
    requiredBy?: string[];
    requires?: string[];
    after?: string[];
    /** 本单元依赖的单元（list-dependencies） */
    dependencies?: string[];
    /** 依赖本单元的单元（list-dependencies --reverse） */
    dependents?: string[];
    /** systemctl status 原文 */
    rawStatus?: string;
    /** 单元文件原文（systemctl cat） */
    unitFile?: string;
    protectedService?: boolean;
    protectionHint?: string;
  }

  /** journald 日志行（结构化） */
  export interface SysLogLine {
    /** 已格式化的本地时间串 */
    time?: string;
    /** epoch 毫秒 */
    timestamp?: number;
    pid?: number;
    identifier?: string;
    /** 原始优先级 0(emerg)~7(debug) */
    level?: number;
    levelName?: string;
    unit?: string;
    message?: string;
  }

  /** 服务操作请求体 */
  export interface ServiceActionBody {
    action: string;
    /** enable/disable 是否带 --now */
    now?: boolean;
    /** kill 动作的信号 */
    signal?: string;
    /** 高危动作需输入服务名确认 */
    confirm?: string;
  }

  /** 服务操作结果 */
  export interface ServiceActionResult {
    name: string;
    action: string;
    ok: boolean;
    exitCode: number;
    stdout?: string;
    stderr?: string;
    /** 动作后回读的真实状态，避免「以为成功了」 */
    activeAfter?: string;
    enabledAfter?: string;
    failedAfter?: boolean;
    message?: string;
    /** true 表示需二次确认，本次未执行 */
    confirmRequired: boolean;
    /** 需现场输入的确认关键字（等于服务名） */
    confirmKeyword?: string;
  }

  /** 批量服务操作请求体 */
  export interface ServiceBatchBody {
    names: string[];
    action: string;
    now?: boolean;
    confirm?: string;
  }

  /** 批量结果中的单项 */
  export interface ServiceBatchItem {
    name: string;
    ok: boolean;
    message?: string;
    activeAfter?: string;
  }

  /** 批量服务操作结果 */
  export interface ServiceBatchResult {
    total: number;
    success: number;
    failed: number;
    items: ServiceBatchItem[];
    confirmRequired: boolean;
    confirmKeyword?: string;
  }

  // ==================== 计划任务 ====================

  /** 错过执行策略：skip=不补跑 / run_once=补跑一次 / catch_up=全补（上限 10 次） */
  export type MisfirePolicy = 'skip' | 'run_once' | 'catch_up';

  /** 并发策略：skip=已在运行则跳过 / queue=等待上一次 / parallel=允许并行 */
  export type OverlapPolicy = 'skip' | 'queue' | 'parallel';

  /** 计划任务 */
  export interface CronJob {
    id?: string;
    name: string;
    cronExpr: string;
    /** 表达式的人话描述（后端保存时生成） */
    humanExpr?: string;
    command: string;
    timeoutSec: number;
    status: number;
    misfirePolicy?: MisfirePolicy;
    overlapPolicy?: OverlapPolicy;
    failCount?: number;
    maxFail?: number;
    /** 最近一次退出码：0 成功 / -1 异常 / -2 超时 / -3 并发跳过 */
    lastExitCode?: number;
    lastDurationMs?: number;
    running?: number;
    runningLogId?: string;
    lockUntil?: string;
    remark?: string;
    lastRunAt?: string;
    nextRunAt?: string;
    createdAt?: string;
    updatedAt?: string;
  }

  /** 计划任务执行日志 */
  export interface CronLog {
    id: string;
    jobId: string;
    jobName: string;
    exitCode: number;
    triggerType?: 'cron' | 'manual' | 'retry';
    operator?: string;
    timedOut?: number;
    truncated?: number;
    output: string;
    startedAt: string;
    finishedAt?: string;
    durationMs: number;
  }

  /** 计划任务总览统计 */
  export interface CronSummary {
    total: number;
    enabled: number;
    disabled: number;
    running: number;
    failed24h: number;
    success24h: number;
    lastRunAt?: string;
    lastResult?: string;
    nextRunAt?: string;
  }

  /** cron 表达式预览（校验 + 人话 + 未来 N 次执行时间） */
  export interface CronPreview {
    valid: boolean;
    cronExpr: string;
    humanExpr?: string;
    nextTimes: string[];
    message?: string;
  }

  /** 可执行命令白名单（含宿主机可用性） */
  export interface CronWhitelist {
    allowed: string[];
    available: string[];
    missing: string[];
    hostAvailable: boolean;
    hostMessage?: string;
  }

  // ==================== 防火墙 ====================

  /** 防火墙状态 */
  export interface FirewallStatus {
    backend: 'ufw' | 'firewalld' | 'none';
    active: boolean;
    rules: FirewallRule[];
  }

  /** 防火墙规则 */
  export interface FirewallRule {
    id: string;
    port: string;
    action: string;
    source: string;
  }

  /** 防火墙规则写入 */
  export interface FirewallRuleBody {
    port: number;
    protocol: 'tcp' | 'udp';
    action: 'allow' | 'deny';
    source?: string;
  }
}

// ==================== 宿主执行通道 ====================

/** 取通道能力快照（带短缓存） */
export async function getHostCapabilityApi() {
  return requestClient.get<OpsApi.HostCapability>('/ops/host/capability');
}

/** 主动重探通道（安装/修复宿主代理后调用，无需重启面板） */
export async function probeHostCapabilityApi() {
  return requestClient.post<OpsApi.HostCapability>('/ops/host/probe');
}

// ==================== 进程 ====================

export async function getProcessListApi(keyword?: string) {
  return requestClient.get<OpsApi.ProcessInfo[]>('/ops/process/list', {
    params: { keyword },
  });
}

export async function killProcessApi(pid: number) {
  return requestClient.post(`/ops/process/${pid}/kill`);
}

// ==================== systemd 服务 ====================

/** 分页列表（推荐：列表以 list-unit-files 为主表，含未被加载的单元） */
export async function getServicePageApi(params: {
  keyword?: string;
  active?: string;
  unitFileState?: string;
  failedOnly?: boolean;
  includeAlias?: boolean;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<{
    records: OpsApi.ServiceVO[];
    total: number;
    pageNum: number;
    pageSize: number;
  }>('/ops/service/page', { params });
}

/** 失败单元聚合（页面顶部红色告警条） */
export async function getServiceFailedApi() {
  return requestClient.get<OpsApi.ServiceVO[]>('/ops/service/failed');
}

/** 总览统计（页面顶部统计卡） */
export async function getServiceSummaryApi() {
  return requestClient.get<OpsApi.ServiceSummary>('/ops/service/summary');
}

/** 保护清单（前端提前标识，避免点下去才被拦） */
export async function getServiceProtectedApi() {
  return requestClient.get<string[]>('/ops/service/protected');
}

/** 强制刷新后端快照 */
export async function refreshServiceSnapshotApi() {
  return requestClient.post('/ops/service/refresh');
}

/** 全局 daemon-reload */
export async function daemonReloadApi() {
  return requestClient.post('/ops/service/daemon-reload');
}

/** 结构化详情 */
export async function getServiceDetailApi(name: string) {
  return requestClient.get<OpsApi.ServiceDetail>(
    `/ops/service/${encodeURIComponent(name)}/detail`,
  );
}

/** 服务日志（结构化 journal 行） */
export async function getServiceLogsApi(
  name: string,
  params?: {
    lines?: number;
    since?: string;
    until?: string;
    keyword?: string;
    minLevel?: number;
  },
) {
  return requestClient.get<OpsApi.SysLogLine[]>(
    `/ops/service/${encodeURIComponent(name)}/logs`,
    { params },
  );
}

/** 单服务操作（返回动作后回读状态；confirmRequired 时未执行） */
export async function serviceActionApi(
  name: string,
  body: OpsApi.ServiceActionBody,
) {
  return requestClient.post<OpsApi.ServiceActionResult>(
    `/ops/service/${encodeURIComponent(name)}/action`,
    body,
  );
}

/** 批量服务操作（逐个执行，不因单个失败而中止） */
export async function serviceBatchApi(body: OpsApi.ServiceBatchBody) {
  return requestClient.post<OpsApi.ServiceBatchResult>(
    '/ops/service/batch',
    body,
  );
}

/** 旧接口：原文状态（保留兼容） */
export async function getServiceRawStatusApi(name: string) {
  return requestClient.get<string>(`/ops/service/${encodeURIComponent(name)}`);
}

// ==================== 计划任务 ====================

export async function getCronJobPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: number;
  lastResult?: string;
}) {
  return requestClient.get('/ops/cron/page', { params });
}

/** 总览统计（页面顶部统计卡） */
export async function getCronSummaryApi() {
  return requestClient.get<OpsApi.CronSummary>('/ops/cron/summary');
}

/**
 * cron 表达式校验 + 预览。
 * valid=false 时 message 为具体原因，表单据此在保存前拦截。
 */
export async function previewCronExprApi(body: {
  cronExpr: string;
  count?: number;
}) {
  return requestClient.post<OpsApi.CronPreview>('/ops/cron/preview', body);
}

export async function getCronWhitelistApi() {
  return requestClient.get<OpsApi.CronWhitelist>('/ops/cron/commands/whitelist');
}

export async function createCronJobApi(body: OpsApi.CronJob) {
  return requestClient.post('/ops/cron', body);
}

export async function updateCronJobApi(body: OpsApi.CronJob) {
  return requestClient.put('/ops/cron', body);
}

export async function deleteCronJobApi(id: string) {
  return requestClient.delete(`/ops/cron/${id}`);
}

/** 批量删除 */
export async function batchDeleteCronJobApi(ids: string[]) {
  return requestClient.delete('/ops/cron/batch', { data: ids });
}

/**
 * 列表内快速启停（启用时后端会立即重算下次执行时间）。
 *
 * 走通用 request 而非 patch 方法：`@vben/request` 的 RequestClient 只暴露
 * get/post/put/delete/request，没有 patch，故显式传 method。
 */
export async function setCronJobStatusApi(id: string, status: number) {
  return requestClient.request(`/ops/cron/${id}/status`, {
    method: 'PATCH',
    data: { status },
  });
}

export async function runCronJobApi(id: string) {
  return requestClient.post<number>(`/ops/cron/${id}/run`);
}

export async function getCronLogPageApi(
  id: string,
  params: {
    pageNum?: number;
    pageSize?: number;
    result?: string;
  },
) {
  return requestClient.get(`/ops/cron/${id}/logs`, { params });
}

/** 单条执行日志详情（轮询直到 finishedAt 非空 = 执行结束） */
export async function getCronLogDetailApi(id: string, logId: string) {
  return requestClient.get<OpsApi.CronLog>(`/ops/cron/${id}/logs/${logId}`);
}

/** 下载单次执行的完整输出 */
export function cronLogDownloadUrl(id: string, logId: string) {
  return `/ops/cron/${id}/logs/${logId}/download`;
}

/** 清空某任务的执行日志 */
export async function clearCronLogsApi(id: string) {
  return requestClient.delete(`/ops/cron/${id}/logs`);
}

// ==================== 防火墙 ====================

export async function getFirewallStatusApi() {
  return requestClient.get<OpsApi.FirewallStatus>('/ops/firewall/status');
}

export async function addFirewallRuleApi(body: OpsApi.FirewallRuleBody) {
  return requestClient.post('/ops/firewall/rule', body);
}

export async function deleteFirewallRuleApi(body: OpsApi.FirewallRuleBody) {
  return requestClient.delete('/ops/firewall/rule', { data: body });
}
