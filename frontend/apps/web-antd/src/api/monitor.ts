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

  /**
   * 单块网卡明细（与后端 NetworkInfo.NetInterface 一致）。
   *
   * <p>容器部署时后端经 HOST_SYSROOT 读取宿主机 /sys 与 /proc，故这里看到的是
   * 宿主机内核视角的全部接口，包含物理网卡、网桥与容器 veth。
   */
  export interface NetInterface {
    /** 内核接口名，如 enp6s0 / docker0 / br-xxxx / vethxxxx */
    name: string;
    /** 接口索引 ifindex */
    index: number;
    /** 接口类型：physical / bond / bridge / veth / tunnel / virtual / loopback */
    category: string;
    /** 接口类型中文描述（含 Docker 语义） */
    typeLabel: string;
    /** 管理状态：up / down / unknown / lowerlayerdown 等 */
    operState: string;
    /** 是否可用：管理 UP 且链路连通 */
    up: boolean;
    /** 管理 UP */
    adminUp: boolean;
    /** 是否检测到载波（物理链路连通） */
    carrier: boolean;
    /** 是否回环接口 */
    loopback: boolean;
    /** 是否为网桥设备 */
    bridge: boolean;
    /** 是否为绑定（bond）设备 */
    bond: boolean;
    /** 所属上层设备名（veth 挂在网桥上时为其网桥名） */
    master: string;
    /** 网桥端口成员名列表（仅网桥非空） */
    bridgePorts: string[];
    /** 是否与 Docker 相关（docker0 / br-* 网桥及其上的 veth） */
    dockerRelated: boolean;
    /** 关联的 Docker 网络名（能解析到时非空） */
    dockerNetwork: string;
    /** MAC 地址 */
    mac: string;
    /** MTU */
    mtu: number;
    /** 主 IPv4 地址（兼容字段） */
    ipv4: string;
    /** 主地址 CIDR：优先 IPv4，无 IPv4 时取首个 IPv6 */
    cidr: string;
    /** 全部 IPv4 地址（带前缀长度） */
    ipv4List: string[];
    /** 全部 IPv6 地址（带前缀长度） */
    ipv6List: string[];
    /** 链路速率 Mbps（未知为 -1） */
    speed: number;
    /** 双工模式：full / half / unknown */
    duplex: string;
    /** 内核驱动名，如 r8169 / bridge / veth */
    driver: string;
    /** 总线地址（物理网卡为 PCI 槽位） */
    busInfo: string;
    /** PCI 设备标识（厂商:设备，如 10EC:8125） */
    vendorId: string;
    /** 厂商名（常见厂商映射为可读名称） */
    vendor: string;
    /** 接口别名 ifalias（未设置为空串） */
    alias: string;
    /** 累计接收字节 */
    rxBytes: number;
    /** 累计发送字节 */
    txBytes: number;
    /** 累计接收包数 */
    rxPackets: number;
    /** 累计发送包数 */
    txPackets: number;
    /** 接收错误数 */
    rxErrors: number;
    /** 发送错误数 */
    txErrors: number;
    /** 接收丢弃数 */
    rxDropped: number;
    /** 发送丢弃数 */
    txDropped: number;
    /** 接收速率 KB/s（差分值，首帧为 0） */
    rxRate: number;
    /** 发送速率 KB/s（差分值，首帧为 0） */
    txRate: number;
    /** 接收包速率 包/s（差分值） */
    rxPacketRate: number;
    /** 发送包速率 包/s（差分值） */
    txPacketRate: number;
  }

  /** 容器在 Docker 网络中的端点 */
  export interface NetworkAttachment {
    /** 容器 ID（12 位短 ID） */
    containerId: string;
    /** 容器名 */
    containerName: string;
    /** 容器在该网络内的 IPv4（带掩码长度） */
    ipv4: string;
    /** 容器端点 MAC 地址 */
    mac: string;
  }

  /** Docker 虚拟网络（对应 docker network inspect 的关键字段） */
  export interface DockerNetwork {
    /** 网络 ID（12 位短 ID） */
    id: string;
    /** 网络名（bridge / host / none / 自定义名） */
    name: string;
    /** 网络驱动：bridge / host / overlay / null */
    driver: string;
    /** 作用域：local / swarm */
    scope: string;
    /** 是否内部网络 */
    internal: boolean;
    /** 是否允许手动挂载容器 */
    attachable: boolean;
    /** 是否启用 IPv6 */
    ipv6Enabled: boolean;
    /** 子网 CIDR，如 172.22.0.0/16 */
    subnet: string;
    /** 网关地址 */
    gateway: string;
    /** 宿主侧网桥名（无网桥时为空串） */
    bridgeName: string;
    /** 网络创建时间（毫秒时间戳，未知为 0） */
    createdAt: number;
    /** 接入本网络的容器数量 */
    containerCount: number;
    /** 接入本网络的容器端点列表 */
    containers: NetworkAttachment[];
  }

  /** 网络汇总统计 */
  export interface NetworkSummary {
    /** 接口总数（不含回环） */
    total: number;
    /** 可用接口数（管理 UP 且链路连通） */
    up: number;
    /** 物理网卡数 */
    physical: number;
    /** 网桥数 */
    bridge: number;
    /** 容器虚拟网卡（veth）数 */
    veth: number;
    /** 与 Docker 相关的接口数 */
    dockerRelated: number;
    /** Docker 网络数 */
    dockerNetworks: number;
    /** 接入 Docker 网络的容器数（按容器去重） */
    dockerContainers: number;
    /** 全部接口累计接收字节 */
    totalRxBytes: number;
    /** 全部接口累计发送字节 */
    totalTxBytes: number;
    /** 全部接口实时接收速率合计 KB/s */
    rxRate: number;
    /** 全部接口实时发送速率合计 KB/s */
    txRate: number;
    /** 默认网关（IPv4，无默认路由为空串） */
    defaultGateway: string;
    /** 默认出口网卡名 */
    defaultInterface: string;
  }

  /** 网络信息快照（主机网卡明细 + Docker 虚拟网络 + 汇总） */
  export interface NetworkInfo {
    /** 采集时间戳（毫秒） */
    ts: number;
    summary: NetworkSummary;
    interfaces: NetInterface[];
    dockerNetworks: DockerNetwork[];
    /** 采集异常时的可读说明（正常为 null） */
    error: null | string;
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
    dockerNetworks: DockerNetwork[];
    networkSummary: NetworkSummary;
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

/** 网络信息快照（宿主机网卡明细 + Docker 虚拟网络 + 汇总统计） */
export async function getMonitorNetworkApi() {
  return requestClient.get<MonitorApi.NetworkInfo>('/monitor/network');
}
