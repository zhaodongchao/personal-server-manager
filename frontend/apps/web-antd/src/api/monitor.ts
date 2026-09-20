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
    /** 挂载点 */
    mount: string;
    /** 挂载源设备，如 /dev/mapper/vg0-root */
    source: string;
    fsType: string;
    /** 所属 LVM 卷组（非 LVM 挂载为空串） */
    vg: string;
    totalBytes: number;
    usableBytes: number;
    usage: number;
  }

  /** 物理磁盘详细信息（与后端 MonitorOverview.PhysicalDisk 一致） */
  export interface PhysicalDisk {
    name: string;
    model: string;
    serial: string;
    sizeBytes: number;
    partitions: DiskPartition[];
  }

  export interface DiskPartition {
    name: string;
    mount: string;
    sizeBytes: number;
    type: string;
    /** 分区作为 LVM 物理卷时所属卷组（非 PV 为空串） */
    vg: string;
  }

  /** Device Mapper 设备（LVM 逻辑卷映射 / dm-* 等） */
  export interface DeviceMapper {
    name: string;
    /** 所属 LVM 卷组（非 LVM 设备为空串） */
    vg: string;
    sizeBytes: number;
    fsType: string;
    mount: string;
  }

  /** LVM 信息（与后端 MonitorOverview.LvmInfo 一致） */
  export interface LvmInfo {
    physicalVolumes: LvmPhysicalVolume[];
    volumeGroups: LvmVolumeGroup[];
    logicalVolumes: LvmLogicalVolume[];
  }

  export interface LvmPhysicalVolume {
    name: string;
    vg: string;
    sizeBytes: number;
    freeBytes: number;
  }

  export interface LvmVolumeGroup {
    name: string;
    pvCount: number;
    lvCount: number;
    sizeBytes: number;
    freeBytes: number;
  }

  export interface LvmLogicalVolume {
    /** 设备映射名，如 vg0-root（对应 /dev/mapper/vg0-root） */
    name: string;
    vg: string;
    sizeBytes: number;
    /** 文件系统类型（未格式化为空串） */
    fsType: string;
    /** 挂载点（未挂载为空串） */
    mount: string;
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
    physicalDisks: PhysicalDisk[];
    deviceMappers: DeviceMapper[];
    lvm: LvmInfo;
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
