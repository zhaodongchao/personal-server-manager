import { requestClient } from '#/api/request';

export namespace MonitorApi {
  /** 实时监控帧（与后端 MetricFrame 一致） */
  export interface MetricFrame {
    ts: number;
    uptimeSeconds: number;
    cpuUsage: number;
    memTotal: number;
    memUsed: number;
    memUsage: number;
    loadAvg1: number;
    loadAvg5: number;
    loadAvg15: number;
    netInRate: number;
    netOutRate: number;
    diskTotal: number;
    diskUsed: number;
    diskUsage: number;
  }

  export interface DiskInfo {
    mount: string;
    fsType: string;
    totalBytes: number;
    usableBytes: number;
    usage: number;
  }

  export interface NetInterface {
    name: string;
    ipv4: string;
    speed: number;
  }

  /** 系统概览 */
  export interface MonitorOverview {
    hostname: string;
    os: string;
    kernel: string;
    cpuModel: string;
    cpuPhysicalCores: number;
    cpuLogicalCores: number;
    disks: DiskInfo[];
    interfaces: NetInterface[];
    latest: MetricFrame;
  }
}

/** 系统概览 */
export async function getMonitorOverviewApi() {
  return requestClient.get<MonitorApi.MonitorOverview>('/monitor/overview');
}

/** 历史帧（最近 minutes 分钟，1-60） */
export async function getMonitorHistoryApi(minutes = 60) {
  return requestClient.get<MonitorApi.MetricFrame[]>('/monitor/history', {
    params: { minutes },
  });
}
