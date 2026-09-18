import { requestClient } from '#/api/request';

export namespace OpsApi {
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

  /** systemd 服务 */
  export interface ServiceInfo {
    name: string;
    load: string;
    active: string;
    sub: string;
    description: string;
    enabled: string;
  }

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

export async function getServiceListApi(keyword?: string) {
  return requestClient.get<OpsApi.ServiceInfo[]>('/ops/service/list', {
    params: { keyword },
  });
}

export async function getServiceDetailApi(name: string) {
  return requestClient.get<string>(`/ops/service/${name}`);
}

export async function serviceActionApi(name: string, action: string) {
  return requestClient.post(`/ops/service/${name}/${action}`);
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

export async function getCronLogPageApi(id: string, params: {
  pageNum?: number;
  pageSize?: number;
}) {
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
