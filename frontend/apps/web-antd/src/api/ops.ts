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

  // ==================== 防火墙 ====================

  /** 防火墙状态 */
  export interface FirewallStatus {
    backend: 'ufw' | 'firewalld' | 'none';
    available: boolean;
    active: boolean;
    version?: string;
    ipv6: boolean;
    logging?: string;
    defaultPolicy?: FirewallDefaultPolicy;
    ruleCount: number;
    rules: FirewallRule[];
    /** 生存线信息：SSH 端口、面板端口、风险提示 */
    guard?: FirewallGuard;
    hostChannel?: HostCapability;
    message?: string;
  }

  /** 默认策略 */
  export interface FirewallDefaultPolicy {
    incoming?: string;
    outgoing?: string;
    routed?: string;
  }

  /**
   * 防火墙规则。
   *
   * <p>注意 action 与 direction 是分开的两个字段：ufw 的动作形如 `ALLOW IN` / `REJECT IN`，
   * 早期版本把 `IN` 混进了 source，列表里会出现「来源 = IN 172.238.101.222」的污染数据。
   */
  export interface FirewallRule {
    /** ufw 规则编号（会随增删重排，删除时必须同时传 fingerprint） */
    no: number;
    to: string;
    toKind: 'any' | 'port' | 'range' | 'multi' | 'app';
    action: 'allow' | 'deny' | 'reject' | 'limit';
    direction: 'in' | 'out' | 'fwd';
    from: string;
    sourceKind: 'any' | 'ip' | 'cidr';
    ipv6: boolean;
    comment: string;
    /** panel=面板写入 / fail2ban / manual=人工带注释 / unknown=无注释 */
    provenance: 'panel' | 'fail2ban' | 'manual' | 'unknown';
    deletable: boolean;
    /** to|action|from，删除时用于校验「编号指向的仍是同一条规则」 */
    fingerprint: string;
  }

  /** 规则目标（四种形态对应 ufw 的四种写法） */
  export interface FirewallRuleTarget {
    kind: 'port' | 'range' | 'multi' | 'any';
    port?: number;
    portEnd?: number;
    ports?: number[];
  }

  /** 防火墙规则写入/删除请求体 */
  export interface FirewallRuleBody {
    target?: FirewallRuleTarget;
    protocol?: 'tcp' | 'udp' | 'any';
    action?: 'allow' | 'deny' | 'reject' | 'limit';
    source?: string;
    comment?: string;
    /** 高危操作的二次确认关键字，如 "SSH 22" */
    confirm?: string;
    /** 删除用：规则编号 */
    no?: number;
    /** 删除用：规则指纹 */
    fingerprint?: string;
    /** 删除外部托管规则（fail2ban）时的强制确认 */
    force?: boolean;
  }

  /** 生存线信息（防锁死） */
  export interface FirewallGuard {
    sshPorts: number[];
    panelPorts: number[];
    clientIp?: string;
    foreignRuleCount: number;
    sshAllowed: boolean;
    warnings: string[];
  }

  /** 防火墙操作结果：命令原文 + 差异 + 看门狗 */
  export interface FirewallActionResult {
    ok: boolean;
    message?: string;
    command?: string;
    changeId?: string;
    diff: string[];
    watchdog?: FirewallWatchdog;
  }

  /** 看门狗状态（变更保护倒计时） */
  export interface FirewallWatchdog {
    id: string;
    secondsLeft: number;
    expiresAt?: string;
    reason?: string;
  }

  /** 防火墙变更记录 */
  export interface FirewallChange {
    id: string;
    backend: string;
    op: string;
    ruleDesc: string;
    diffJson?: string;
    beforeSnapshot?: string;
    afterSnapshot?: string;
    guardAck?: number;
    watchdogSeconds?: number;
    rollbackable?: number;
    rolledBack?: number;
    result?: number;
    errorMsg?: string;
    operator?: string;
    createdAt?: string;
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
// ==================== 防火墙 ====================

/** 防火墙状态：后端类型、开关、默认策略、规则清单、生存线信息 */
export async function getFirewallStatusApi() {
  return requestClient.get<OpsApi.FirewallStatus>('/ops/firewall/status');
}

/** 生存线信息：SSH 端口 / 面板端口 / 来源 IP / 风险提示 */
export async function getFirewallGuardApi() {
  return requestClient.get<OpsApi.FirewallGuard>('/ops/firewall/guard');
}

/** ufw show raw 原文（排障用） */
export async function getFirewallRawApi() {
  return requestClient.get<string>('/ops/firewall/raw');
}

export async function addFirewallRuleApi(body: OpsApi.FirewallRuleBody) {
  return requestClient.post<OpsApi.FirewallActionResult>(
    '/ops/firewall/rule',
    body,
  );
}

/** 按编号 + 指纹删除（指纹不匹配 = 编号已漂移，后端拒绝） */
export async function deleteFirewallRuleApi(body: OpsApi.FirewallRuleBody) {
  return requestClient.request<OpsApi.FirewallActionResult>(
    '/ops/firewall/rule',
    { method: 'DELETE', data: body },
  );
}

/** 启用防火墙（L3，默认挂看门狗） */
export async function enableFirewallApi(confirm?: string) {
  return requestClient.post<OpsApi.FirewallActionResult>(
    '/ops/firewall/enable',
    { confirm },
  );
}

/** 停用防火墙（L3） */
export async function disableFirewallApi(confirm?: string) {
  return requestClient.post<OpsApi.FirewallActionResult>(
    '/ops/firewall/disable',
    { confirm },
  );
}

/** 设置默认策略（L3） */
export async function setFirewallDefaultPolicyApi(body: {
  incoming?: string;
  outgoing?: string;
  routed?: string;
  confirm?: string;
}) {
  return requestClient.post<OpsApi.FirewallActionResult>(
    '/ops/firewall/default-policy',
    body,
  );
}

export async function reloadFirewallApi() {
  return requestClient.post<OpsApi.FirewallActionResult>('/ops/firewall/reload');
}

/** 变更历史（含前后快照与 diff） */
export async function getFirewallChangesApi(params: {
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<{
    records: OpsApi.FirewallChange[];
    total: number;
    pageNum: number;
    pageSize: number;
  }>('/ops/firewall/changes', { params });
}

/** 单次变更详情（diff / 前后快照 / undo 命令） */
export async function getFirewallChangeDetailApi(id: string) {
  return requestClient.get<OpsApi.FirewallChange>(
    `/ops/firewall/changes/${id}`,
  );
}

/** 回滚某次变更（L3） */
export async function rollbackFirewallChangeApi(id: string, confirm?: string) {
  return requestClient.post<OpsApi.FirewallActionResult>(
    `/ops/firewall/changes/${id}/rollback`,
    { confirm },
  );
}

/** 看门狗倒计时：未启用返回 null */
export async function getFirewallWatchdogApi() {
  return requestClient.get<OpsApi.FirewallWatchdog>('/ops/firewall/guard/watchdog');
}

/** 手动注册看门狗 */
export async function armFirewallWatchdogApi(body: {
  seconds?: number;
  action?: string;
}) {
  return requestClient.post<OpsApi.FirewallWatchdog>(
    '/ops/firewall/guard/watchdog',
    body,
  );
}

/** 「保留变更」——撤销看门狗，不再自动回滚 */
export async function confirmFirewallWatchdogApi() {
  return requestClient.post('/ops/firewall/guard/confirm');
}
