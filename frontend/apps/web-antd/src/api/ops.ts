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

  /** 计划任务 */
  export interface CronJob {
    id?: string;
    name: string;
    cronExpr: string;
    command: string;
    timeoutSec: number;
    status: number;
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
    output: string;
    startedAt: string;
    finishedAt?: string;
    durationMs: number;
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
}) {
  return requestClient.get('/ops/cron/page', { params });
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

export async function runCronJobApi(id: string) {
  return requestClient.post<number>(`/ops/cron/${id}/run`);
}

export async function getCronLogPageApi(
  id: string,
  params: {
    pageNum?: number;
    pageSize?: number;
  },
) {
  return requestClient.get(`/ops/cron/${id}/logs`, { params });
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
