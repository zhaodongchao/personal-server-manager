<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { MonitorApi } from '#/api';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Card,
  Col,
  Descriptions,
  DescriptionsItem,
  Empty,
  InputSearch,
  Progress,
  RadioButton,
  RadioGroup,
  Row,
  Space,
  Statistic,
  Switch,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'ant-design-vue';

import {
  getMonitorHistoryApi,
  getMonitorNetworkApi,
  getMonitorOverviewApi,
} from '#/api';

defineOptions({ name: 'MonitorIndex' });

/** 滚动窗口保留帧数（5s/帧 ≈ 20 分钟） */
const MAX_POINTS = 240;

/** 网络快照轮询间隔（与后端 NetworkCollector 采集节奏一致） */
const NET_POLL_MS = 5000;

/** 接口类型中文名（后端 category → 展示文案） */
const CATEGORY_LABEL: Record<string, string> = {
  bond: '绑定（Bond）',
  bridge: '网桥',
  loopback: '回环',
  physical: '物理网卡',
  tunnel: '隧道',
  veth: '容器虚拟网卡',
  virtual: '虚拟接口',
};

/** 接口类型标签配色 */
const CATEGORY_COLOR: Record<string, string> = {
  bond: 'purple',
  bridge: 'geekblue',
  loopback: 'default',
  physical: 'blue',
  tunnel: 'cyan',
  veth: 'orange',
  virtual: 'gold',
};

const overview = ref<MonitorApi.MonitorOverview>();
const frames = ref<MonitorApi.MetricFrame[]>([]);
const wsState = ref<'connected' | 'connecting' | 'offline'>('offline');

/** 网络信息快照（主机网卡 + Docker 虚拟网络） */
const network = ref<MonitorApi.NetworkInfo>();
/** 网络接口类型筛选：all 或具体 category */
const netFilter = ref('all');
/** 网络接口关键字（名称 / IP / MAC / Docker 网络） */
const netKeyword = ref('');
/** 是否仅显示与 Docker 相关的接口 */
const netDockerOnly = ref(false);
/** 轮询重入保护 */
let netLoading = false;

const perfChartRef = ref<EchartsUIType>();
const netChartRef = ref<EchartsUIType>();
const { renderEcharts: renderPerf, updateData: updatePerf } =
  useEcharts(perfChartRef);
const { renderEcharts: renderNet, updateData: updateNet } =
  useEcharts(netChartRef);

let ws: null | WebSocket = null;
let reconnectTimer: null | ReturnType<typeof setTimeout> = null;
let netTimer: null | ReturnType<typeof setInterval> = null;

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

/** 后端速率统一为 KB/s，>1024 时自动升位 */
function formatRateKb(kb: number): string {
  if (!Number.isFinite(kb) || kb <= 0) return '0 B/s';
  if (kb < 1) return `${Math.round(kb * 1024)} B/s`;
  const units = ['KB/s', 'MB/s', 'GB/s'];
  let v = kb;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v.toFixed(v >= 100 ? 0 : v >= 10 ? 1 : 2)} ${units[i]}`;
}

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d} 天 ${h} 小时`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  return `${m} 分`;
}

const latest = () => frames.value.at(-1);

/** 是否存在 LVM 卷（任一子列表非空） */
const hasLvm = computed(() => {
  const lvm = overview.value?.lvm;
  if (!lvm) return false;
  const pvCount = lvm.physicalVolumes?.length ?? 0;
  const vgCount = lvm.volumeGroups?.length ?? 0;
  const lvCount = lvm.logicalVolumes?.length ?? 0;
  return pvCount > 0 || vgCount > 0 || lvCount > 0;
});

/** 文件系统树节点 */
interface FsTreeNode {
  key: string;
  /** 展示名：根为 "/"，子节点为末段目录名 */
  name: string;
  /** 挂载源设备，如 /dev/mapper/vg0-root */
  source: string;
  fsType: string;
  /** 所属 LVM 卷组（非 LVM 挂载为空串） */
  vg: string;
  totalBytes: number;
  usableBytes: number;
  usage: number;
  children: FsTreeNode[];
}

/** 计算挂载点在文件系统树中的父挂载点（最长字面前缀），无则返回 null */
function parentMount(mount: string, disks: MonitorApi.DiskInfo[]): null | string {
  let best: null | string = null;
  for (const d of disks) {
    const p = d.mount;
    if (p === mount) continue;
    // 根 "/" 是任意顶层目录的前缀
    if (p === '/') {
      if (mount.startsWith('/') && mount.length > 1) best = '/';
      continue;
    }
    // 需为严格前缀且其后紧跟路径分隔符，避免 /boot 误匹配 /bootx
    if (
      mount.length > p.length &&
      mount.startsWith(p) &&
      mount.charAt(p.length) === '/' &&
      (best === null || p.length > best.length)
    ) {
      best = p;
    }
  }
  return best;
}

/** 由扁平挂载点列表构建文件系统树（root 在前） */
const fsTree = computed<FsTreeNode[]>(() => {
  const disks = overview.value?.disks ?? [];
  if (disks.length === 0) return [];
  const nodeMap = new Map<string, FsTreeNode>();
  for (const d of disks) {
    nodeMap.set(d.mount, {
      key: d.mount,
      name: d.mount === '/' ? '/' : d.mount.split('/').filter(Boolean).at(-1) ?? d.mount,
      source: d.source ?? '',
      fsType: d.fsType,
      vg: d.vg ?? '',
      totalBytes: d.totalBytes,
      usableBytes: d.usableBytes,
      usage: d.usage,
      children: [],
    });
  }
  const roots: FsTreeNode[] = [];
  for (const d of disks) {
    const node = nodeMap.get(d.mount);
    if (!node) continue;
    const parent = parentMount(d.mount, disks);
    if (parent) {
      const pnode = nodeMap.get(parent);
      if (pnode) {
        pnode.children.push(node);
        continue;
      }
    }
    roots.push(node);
  }
  // 按名称排序（根优先），children 递归排序
  const sortRec = (arr: FsTreeNode[]) => {
    arr.sort((a, b) => {
      // 根节点 "/" 置顶
      if (a.name === '/') return b.name === '/' ? 0 : -1;
      if (b.name === '/') return 1;
      return a.name.localeCompare(b.name);
    });
    arr.forEach((n) => sortRec(n.children));
  };
  sortRec(roots);
  return roots;
});

/* ------------------------------ 网络维度 ------------------------------ */

/** 网络快照（优先用独立接口，未就绪时回退概览内嵌数据） */
const netInfo = computed<MonitorApi.NetworkInfo | undefined>(() => {
  if (network.value) return network.value;
  const ov = overview.value;
  if (!ov) return undefined;
  return {
    dockerNetworks: ov.dockerNetworks ?? [],
    error: null,
    interfaces: ov.interfaces ?? [],
    summary: ov.networkSummary,
    ts: Date.now(),
  };
});

const netSummary = computed(() => netInfo.value?.summary);

/** 按类型聚合的筛选项（含数量），按数量倒序 */
const categoryOptions = computed(() => {
  const list = netInfo.value?.interfaces ?? [];
  const map = new Map<string, number>();
  for (const item of list) {
    map.set(item.category, (map.get(item.category) ?? 0) + 1);
  }
  return [...map.entries()]
    .map(([key, count]) => ({
      count,
      key,
      label: CATEGORY_LABEL[key] ?? key,
    }))
    .sort((a, b) => b.count - a.count);
});

/** 应用类型 / Docker / 关键字三重过滤后的接口列表 */
const filteredInterfaces = computed(() => {
  let list = netInfo.value?.interfaces ?? [];
  if (netFilter.value !== 'all') {
    list = list.filter((item) => item.category === netFilter.value);
  }
  if (netDockerOnly.value) {
    list = list.filter((item) => item.dockerRelated);
  }
  const kw = netKeyword.value.trim().toLowerCase();
  if (kw) {
    list = list.filter(
      (item) =>
        item.name.toLowerCase().includes(kw) ||
        (item.cidr ?? '').toLowerCase().includes(kw) ||
        (item.ipv4List ?? []).some((a) => a.toLowerCase().includes(kw)) ||
        (item.ipv6List ?? []).some((a) => a.toLowerCase().includes(kw)) ||
        (item.mac ?? '').toLowerCase().includes(kw) ||
        (item.dockerNetwork ?? '').toLowerCase().includes(kw) ||
        (item.driver ?? '').toLowerCase().includes(kw),
    );
  }
  return list;
});

const netSummaryText = computed(() => {
  const s = netSummary.value;
  if (!s) return '采集中...';
  return `接口 ${s.total} 个 · 已启用 ${s.up} · 物理网卡 ${s.physical} · Docker 网络 ${s.dockerNetworks}`;
});

const netUpdatedText = computed(() => {
  const ts = netInfo.value?.ts;
  if (!ts) return '';
  return `更新于 ${new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })}`;
});

/** 展开行展示的 IPv6 列表（无则为空） */
function ipv6Text(list?: string[]): string {
  return list && list.length > 0 ? list.join('\n') : '-';
}

/** 网桥端口 / 上层设备摘要 */
function linkSummary(item: MonitorApi.NetInterface): string {
  if (item.bridge && item.bridgePorts?.length) {
    return `端口 ${item.bridgePorts.length} 个`;
  }
  if (item.master) return `→ ${item.master}`;
  if (item.dockerNetwork) return item.dockerNetwork;
  return '-';
}

function categoryColor(category?: string): string {
  if (!category) return 'default';
  return CATEGORY_COLOR[category] ?? 'default';
}

/** 拉取一次网络快照，失败不阻塞页面其余部分 */
async function loadNetwork() {
  if (netLoading) return;
  netLoading = true;
  try {
    network.value = await getMonitorNetworkApi();
  } catch {
    // 网络快照异常时保留上一次结果，页面顶部错误条由 error 字段驱动
  } finally {
    netLoading = false;
  }
}

function timeLabels(): string[] {
  return frames.value.map((f) =>
    new Date(f.ts).toLocaleTimeString('zh-CN', { hour12: false }),
  );
}

function baseChartOption(labels: string[]) {
  return {
    grid: {
      bottom: 0,
      containLabel: true,
      left: '1%',
      right: '2%',
      top: '12%',
    },
    legend: { data: [] as string[], top: 0 },
    tooltip: { trigger: 'axis' as const },
    xAxis: {
      axisTick: { show: false },
      boundaryGap: false,
      data: labels,
      type: 'category' as const,
    },
    yAxis: { type: 'value' as const },
  };
}

function renderCharts() {
  const labels = timeLabels();
  renderPerf({
    ...baseChartOption(labels),
    legend: { data: ['CPU 使用率', '内存使用率'], top: 0 },
    series: [
      {
        areaStyle: { opacity: 0.15 },
        data: frames.value.map((f) => f.cpuUsage.toFixed(1)),
        itemStyle: { color: '#5ab1ef' },
        name: 'CPU 使用率',
        smooth: true,
        type: 'line',
        yAxisIndex: 0,
      },
      {
        areaStyle: { opacity: 0.15 },
        data: frames.value.map((f) => f.memUsage.toFixed(1)),
        itemStyle: { color: '#019680' },
        name: '内存使用率',
        smooth: true,
        type: 'line',
      },
    ],
    yAxis: { axisLabel: { formatter: '{value}%' }, max: 100, type: 'value' },
  });
  renderNet({
    ...baseChartOption(labels),
    legend: { data: ['下载速率', '上传速率'], top: 0 },
    series: [
      {
        areaStyle: { opacity: 0.15 },
        data: frames.value.map((f) => Number(f.netInRate.toFixed(1))),
        itemStyle: { color: '#5470c6' },
        name: '下载速率',
        smooth: true,
        type: 'line',
      },
      {
        areaStyle: { opacity: 0.15 },
        data: frames.value.map((f) => Number(f.netOutRate.toFixed(1))),
        itemStyle: { color: '#ee6666' },
        name: '上传速率',
        smooth: true,
        type: 'line',
      },
    ],
    yAxis: { axisLabel: { formatter: '{value} KB/s' }, type: 'value' },
  });
}

function appendFrames(...incoming: MonitorApi.MetricFrame[]) {
  frames.value.push(...incoming);
  if (frames.value.length > MAX_POINTS) {
    frames.value.splice(0, frames.value.length - MAX_POINTS);
  }
}

function updateCharts() {
  const labels = timeLabels();
  updatePerf({
    series: [
      { data: frames.value.map((f) => f.cpuUsage.toFixed(1)), name: 'CPU 使用率' },
      {
        data: frames.value.map((f) => f.memUsage.toFixed(1)),
        name: '内存使用率',
      },
    ],
    xAxis: { data: labels, type: 'category' },
  });
  updateNet({
    series: [
      {
        data: frames.value.map((f) => Number(f.netInRate.toFixed(1))),
        name: '下载速率',
      },
      {
        data: frames.value.map((f) => Number(f.netOutRate.toFixed(1))),
        name: '上传速率',
      },
    ],
    xAxis: { data: labels, type: 'category' },
  });
}

function connectWs() {
  const accessStore = useAccessStore();
  const token = accessStore.accessToken;
  if (!token) return;

  wsState.value = 'connecting';
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(
    `${proto}://${window.location.host}/ws/monitor?token=${encodeURIComponent(token)}`,
  );
  ws.onopen = () => {
    wsState.value = 'connected';
  };
  ws.onmessage = (event) => {
    try {
      const frame: MonitorApi.MetricFrame = JSON.parse(event.data);
      if (overview.value) {
        overview.value.latest = frame;
      }
      appendFrames(frame);
      updateCharts();
    } catch {
      // 忽略坏帧
    }
  };
  ws.onclose = () => {
    wsState.value = 'offline';
    scheduleReconnect();
  };
  ws.onerror = () => {
    ws?.close();
  };
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectWs();
  }, 5000);
}

onMounted(async () => {
  // 并行加载概览与历史
  const [overviewRes, historyRes] = await Promise.all([
    getMonitorOverviewApi(),
    getMonitorHistoryApi(20),
  ]);
  overview.value = overviewRes;
  // 用概览内嵌网络数据先渲染首屏，再由独立接口刷新
  loadNetwork();
  netTimer = setInterval(loadNetwork, NET_POLL_MS);
  // 历史为最新在前，反转为时间正序
  const history = [...historyRes].reverse();
  if (history.length > 0) {
    appendFrames(...history);
  } else if (overviewRes.latest) {
    appendFrames(overviewRes.latest);
  }
  renderCharts();
  connectWs();
});

onBeforeUnmount(() => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (netTimer) {
    clearInterval(netTimer);
    netTimer = null;
  }
  if (ws) {
    ws.onclose = null;
    ws.close();
    ws = null;
  }
});
</script>

<template>
  <div class="p-4 md:p-5">
    <!-- 概览卡片 -->
    <Row :gutter="[12, 12]">
      <Col :lg="6" :md="12" :xs="24">
        <Card>
          <Statistic
            :value="latest()?.cpuUsage ?? 0"
            :precision="1"
            suffix="%"
            title="CPU 使用率"
          />
          <Progress
            :percent="latest()?.cpuUsage ?? 0"
            :show-info="false"
            size="small"
            status="normal"
          />
          <div class="text-xs text-gray-400">
            {{ overview?.cpuModel || '加载中...' }}
          </div>
        </Card>
      </Col>
      <Col :lg="6" :md="12" :xs="24">
        <Card>
          <Statistic
            :value="latest()?.memUsage ?? 0"
            :precision="1"
            suffix="%"
            title="内存使用率"
          />
          <Progress
            :percent="latest()?.memUsage ?? 0"
            :show-info="false"
            size="small"
            status="normal"
          />
          <div class="text-xs text-gray-400">
            {{ formatBytes(latest()?.memUsed ?? 0) }} /
            {{ formatBytes(latest()?.memTotal ?? 0) }}
          </div>
        </Card>
      </Col>
      <Col :lg="6" :md="12" :xs="24">
        <Card>
          <Statistic
            :value="latest()?.diskUsage ?? 0"
            :precision="1"
            suffix="%"
            title="根分区使用率"
          />
          <Progress
            :percent="latest()?.diskUsage ?? 0"
            :show-info="false"
            size="small"
            status="normal"
          />
          <div class="text-xs text-gray-400">
            {{ formatBytes(latest()?.diskUsed ?? 0) }} /
            {{ formatBytes(latest()?.diskTotal ?? 0) }}
          </div>
        </Card>
      </Col>
      <Col :lg="6" :md="12" :xs="24">
        <Card>
          <Statistic
            :value="latest()?.loadAvg1 ?? 0"
            :precision="2"
            title="系统负载（1/5/15 分钟）"
          />
          <div class="mt-1 text-xs text-gray-400">
            {{ (latest()?.loadAvg5 ?? 0).toFixed(2) }} /
            {{ (latest()?.loadAvg15 ?? 0).toFixed(2) }} ·
            {{ overview?.cpuLogicalCores ?? '-' }} 核
          </div>
          <div class="mt-1 text-xs">
            实时通道：
            <span :class="wsState === 'connected' ? 'text-green-500' : 'text-red-500'">
              {{ wsState === 'connected' ? '已连接' : wsState }}
            </span>
          </div>
        </Card>
      </Col>
    </Row>

    <!-- 实时图表 -->
    <Row :gutter="[12, 12]" class="mt-3">
      <Col :lg="12" :xs="24">
        <Card title="CPU / 内存（实时）">
          <EchartsUI ref="perfChartRef" height="300px" />
        </Card>
      </Col>
      <Col :lg="12" :xs="24">
        <Card title="网络速率（实时）">
          <EchartsUI ref="netChartRef" height="300px" />
        </Card>
      </Col>
    </Row>

    <!-- 系统信息 / 磁盘 -->
    <Row :gutter="[12, 12]" class="mt-3">
      <Col :lg="12" :xs="24">
        <Card title="系统信息">
          <Descriptions :column="1" bordered size="small">
            <DescriptionsItem label="主机名">
              {{ overview?.hostname || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="操作系统">
              {{ overview?.os || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="内核版本">
              {{ overview?.kernel || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="CPU 型号">
              {{ overview?.cpuModel || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="物理核 / 逻辑核">
              {{ overview?.cpuPhysicalCores ?? '-' }} /
              {{ overview?.cpuLogicalCores ?? '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="运行时长">
              {{ formatUptime(latest()?.uptimeSeconds ?? 0) }}
            </DescriptionsItem>
          </Descriptions>
        </Card>
      </Col>
      <Col :lg="12" :xs="24">
        <Card title="磁盘">
          <Tabs>
            <TabPane key="disk" tab="物理磁盘">
              <Table
                :data-source="overview?.physicalDisks ?? []"
                :pagination="false"
                row-key="name"
                size="small"
              >
                <Table.Column title="设备">
                  <template #default="{ record }">
                    {{ record.name }}
                  </template>
                </Table.Column>
                <Table.Column title="型号">
                  <template #default="{ record }">
                    {{ record.model || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="序列号">
                  <template #default="{ record }">
                    {{ record.serial || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="容量">
                  <template #default="{ record }">
                    {{ formatBytes(record.sizeBytes) }}
                  </template>
                </Table.Column>
                <template #expandedRowRender="{ record }">
                  <Table
                    :data-source="record.partitions"
                    :pagination="false"
                    row-key="name"
                    size="small"
                  >
                    <Table.Column data-index="name" title="分区" />
                    <Table.Column title="格式化">
                      <template #default="{ record: p }">
                        {{
                          p.vg
                            ? `LVM2 → ${p.vg}`
                            : p.type || '-'
                        }}
                      </template>
                    </Table.Column>
                    <Table.Column data-index="mount" title="挂载点">
                      <template #default="{ record: p }">
                        {{ p.mount || '-' }}
                      </template>
                    </Table.Column>
                    <Table.Column title="大小">
                      <template #default="{ record: p }">
                        {{ formatBytes(p.sizeBytes) }}
                      </template>
                    </Table.Column>
                  </Table>
                </template>
              </Table>
            </TabPane>

            <TabPane key="dm" tab="Device Mapper">
              <div
                v-if="!overview?.deviceMappers?.length"
                class="py-6 text-center text-xs text-gray-400"
              >
                该服务器未配置 Device Mapper 设备。
              </div>
              <Table
                v-else
                :data-source="overview?.deviceMappers ?? []"
                :pagination="false"
                row-key="name"
                size="small"
              >
                <Table.Column title="设备">
                  <template #default="{ record }">
                    {{ record.name }}
                  </template>
                </Table.Column>
                <Table.Column title="卷组">
                  <template #default="{ record }">
                    {{ record.vg || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="容量">
                  <template #default="{ record }">
                    {{ formatBytes(record.sizeBytes) }}
                  </template>
                </Table.Column>
                <Table.Column title="格式化">
                  <template #default="{ record }">
                    {{ record.fsType || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="挂载点">
                  <template #default="{ record }">
                    {{ record.mount || '-' }}
                  </template>
                </Table.Column>
              </Table>
            </TabPane>

            <TabPane key="lvm" tab="LVM">
              <div v-if="!hasLvm" class="py-6 text-center text-xs text-gray-400">
                该服务器未配置 LVM 卷（卷组 / 逻辑卷）。
              </div>
              <template v-else>
                <div class="mb-2 text-xs text-gray-400">物理卷（PV）</div>
                <Table
                  :data-source="overview?.lvm?.physicalVolumes ?? []"
                  :pagination="false"
                  row-key="name"
                  size="small"
                >
                  <Table.Column data-index="name" title="物理卷" />
                  <Table.Column data-index="vg" title="卷组" />
                  <Table.Column title="大小">
                    <template #default="{ record }">
                      {{ formatBytes(record.sizeBytes) }}
                    </template>
                  </Table.Column>
                  <Table.Column title="可用">
                    <template #default="{ record }">
                      {{ formatBytes(record.freeBytes) }}
                    </template>
                  </Table.Column>
                </Table>

                <div class="mb-2 mt-2 text-xs text-gray-400">卷组（VG）</div>
                <Table
                  :data-source="overview?.lvm?.volumeGroups ?? []"
                  :pagination="false"
                  row-key="name"
                  size="small"
                >
                  <Table.Column data-index="name" title="卷组" />
                  <Table.Column
                    data-index="pvCount"
                    title="物理卷数"
                    :width="80"
                  />
                  <Table.Column
                    data-index="lvCount"
                    title="逻辑卷数"
                    :width="80"
                  />
                  <Table.Column title="大小">
                    <template #default="{ record }">
                      {{ formatBytes(record.sizeBytes) }}
                    </template>
                  </Table.Column>
                  <Table.Column title="可用">
                    <template #default="{ record }">
                      {{ formatBytes(record.freeBytes) }}
                    </template>
                  </Table.Column>
                </Table>

                <div class="mb-2 mt-2 text-xs text-gray-400">逻辑卷（LV）</div>
                <Table
                  :data-source="overview?.lvm?.logicalVolumes ?? []"
                  :pagination="false"
                  row-key="name"
                  size="small"
                >
                  <Table.Column data-index="name" title="逻辑卷" />
                  <Table.Column data-index="vg" title="卷组" />
                  <Table.Column title="大小">
                    <template #default="{ record }">
                      {{ formatBytes(record.sizeBytes) }}
                    </template>
                  </Table.Column>
                  <Table.Column title="格式化">
                    <template #default="{ record }">
                      {{ record.fsType || '-' }}
                    </template>
                  </Table.Column>
                  <Table.Column title="挂载点">
                    <template #default="{ record }">
                      {{ record.mount || '-' }}
                    </template>
                  </Table.Column>
                </Table>
              </template>
            </TabPane>

            <TabPane key="fs" tab="文件系统">
              <Table
                :data-source="fsTree"
                :default-expand-all-rows="true"
                :pagination="false"
                row-key="key"
                size="small"
              >
                <Table.Column title="目录">
                  <template #default="{ record }">
                    {{ record.name }}
                  </template>
                </Table.Column>
                <Table.Column title="设备">
                  <template #default="{ record }">
                    {{ record.source || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="文件系统">
                  <template #default="{ record }">
                    {{ record.fsType || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="LVM 卷组">
                  <template #default="{ record }">
                    {{ record.vg || '-' }}
                  </template>
                </Table.Column>
                <Table.Column title="总量">
                  <template #default="{ record }">
                    {{ formatBytes(record.totalBytes) }}
                  </template>
                </Table.Column>
                <Table.Column title="可用">
                  <template #default="{ record }">
                    {{ formatBytes(record.usableBytes) }}
                  </template>
                </Table.Column>
                <Table.Column title="使用率">
                  <template #default="{ record }">
                    <Progress
                      :percent="Number(record.usage.toFixed(1))"
                      size="small"
                      :status="record.usage > 85 ? 'exception' : 'normal'"
                    />
                  </template>
                </Table.Column>
              </Table>
            </TabPane>
          </Tabs>
        </Card>
      </Col>
    </Row>

    <!-- 网络接口与 Docker 虚拟网络（全宽） -->
    <Card class="mt-3">
      <template #title>
        <Space>
          <span>网络接口与 Docker 虚拟网络</span>
          <Tag v-if="netSummary" color="blue">{{ netSummaryText }}</Tag>
        </Space>
      </template>
      <template #extra>
        <span class="text-xs text-gray-400">{{ netUpdatedText }}</span>
      </template>

      <Alert
        v-if="netInfo?.error"
        :message="netInfo.error"
        class="mb-3"
        show-icon
        type="warning"
      />

      <Tabs>
        <!-- 主机网卡明细 -->
        <TabPane key="ifaces">
          <template #tab>
            网络接口明细 ({{ filteredInterfaces.length }})
          </template>
          <Space class="mb-3" :size="12" wrap>
            <RadioGroup v-model:value="netFilter" button-style="solid" size="small">
              <RadioButton value="all">
                全部 ({{ netInfo?.interfaces?.length ?? 0 }})
              </RadioButton>
              <RadioButton
                v-for="opt in categoryOptions"
                :key="opt.key"
                :value="opt.key"
              >
                {{ opt.label }} ({{ opt.count }})
              </RadioButton>
            </RadioGroup>
            <InputSearch
              v-model:value="netKeyword"
              allow-clear
              placeholder="按名称 / IP / MAC / 驱动 / Docker 网络搜索"
              size="small"
              style="max-width: 320px"
            />
            <Space :size="6">
              <Switch v-model:checked="netDockerOnly" size="small" />
              <span class="text-xs">仅看 Docker 相关</span>
            </Space>
          </Space>

          <Table
            :data-source="filteredInterfaces"
            :pagination="false"
            :scroll="{ x: 1180 }"
            row-key="name"
            size="small"
          >
            <Table.Column title="接口">
              <template #default="{ record }">
                <span class="font-medium">{{ record.name }}</span>
                <span class="ml-1 text-xs text-gray-400">#{{ record.index }}</span>
              </template>
            </Table.Column>
            <Table.Column title="类型" :width="140">
              <template #default="{ record }">
                <Tag :color="categoryColor(record.category)">
                  {{ record.typeLabel || record.category }}
                </Tag>
              </template>
            </Table.Column>
            <Table.Column title="状态" :width="130">
              <template #default="{ record }">
                <Tag :color="record.up ? 'green' : 'default'">
                  {{ record.up ? '已启用' : record.operState || '未启用' }}
                </Tag>
                <Tag
                  v-if="record.category === 'physical'"
                  :color="record.carrier ? 'cyan' : 'red'"
                >
                  {{ record.carrier ? '已连线' : '无载波' }}
                </Tag>
              </template>
            </Table.Column>
            <Table.Column title="IPv4" :width="180">
              <template #default="{ record }">
                <span>{{ record.cidr || '-' }}</span>
                <div
                  v-if="(record.ipv4List?.length ?? 0) > 1"
                  class="text-xs text-gray-400"
                >
                  共 {{ record.ipv4List.length }} 个地址
                </div>
              </template>
            </Table.Column>
            <Table.Column title="IPv6" :width="200">
              <template #default="{ record }">
                <span class="break-all">
                  {{ record.ipv6List?.[0] || '-' }}
                </span>
                <div
                  v-if="(record.ipv6List?.length ?? 0) > 1"
                  class="text-xs text-gray-400"
                >
                  共 {{ record.ipv6List.length }} 个地址
                </div>
              </template>
            </Table.Column>
            <Table.Column title="MAC" :width="150">
              <template #default="{ record }">
                {{ record.mac || '-' }}
              </template>
            </Table.Column>
            <Table.Column title="MTU / 速率" :width="120">
              <template #default="{ record }">
                <div>{{ record.mtu || '-' }}</div>
                <div class="text-xs text-gray-400">
                  {{
                    record.speed > 0
                      ? `${record.speed} Mbps`
                      : record.duplex === 'full' || record.duplex === 'half'
                        ? record.duplex
                        : '-'
                  }}
                </div>
              </template>
            </Table.Column>
            <Table.Column title="实时速率" :width="170">
              <template #default="{ record }">
                <div class="text-green-600">
                  ↓ {{ formatRateKb(record.rxRate) }}
                </div>
                <div class="text-blue-600">
                  ↑ {{ formatRateKb(record.txRate) }}
                </div>
              </template>
            </Table.Column>
            <Table.Column title="累计流量" :width="170">
              <template #default="{ record }">
                <div class="text-xs">↓ {{ formatBytes(record.rxBytes) }}</div>
                <div class="text-xs">↑ {{ formatBytes(record.txBytes) }}</div>
              </template>
            </Table.Column>
            <Table.Column title="上层 / Docker" :width="150">
              <template #default="{ record }">
                <Tag v-if="record.dockerRelated" color="orange">Docker</Tag>
                <span class="text-xs">{{ linkSummary(record) }}</span>
              </template>
            </Table.Column>

            <template #expandedRowRender="{ record }">
              <Descriptions :column="3" bordered size="small">
                <DescriptionsItem label="接口索引">
                  {{ record.index }}
                </DescriptionsItem>
                <DescriptionsItem label="类型分类">
                  {{ record.category }}（{{ record.typeLabel }}）
                </DescriptionsItem>
                <DescriptionsItem label="管理状态">
                  {{ record.operState || '-' }} / 管理 {{ record.adminUp ? 'UP' : 'DOWN' }}
                </DescriptionsItem>
                <DescriptionsItem label="内核驱动">
                  {{ record.driver || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="总线地址">
                  {{ record.busInfo || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="设备标识">
                  {{ record.vendorId || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="厂商">
                  {{ record.vendor || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="接口别名">
                  {{ record.alias || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="双工 / MTU">
                  {{ record.duplex || '-' }} / {{ record.mtu || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="上层设备">
                  {{ record.master || '-' }}
                </DescriptionsItem>
                <DescriptionsItem
                  :span="2"
                  label="网桥端口"
                >
                  {{
                    record.bridgePorts?.length
                      ? record.bridgePorts.join(', ')
                      : '-'
                  }}
                </DescriptionsItem>
                <DescriptionsItem label="关联 Docker 网络">
                  {{ record.dockerNetwork || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="收 / 发 包数">
                  {{ record.rxPackets }} / {{ record.txPackets }}
                </DescriptionsItem>
                <DescriptionsItem label="包速率（包/s）">
                  ↓ {{ record.rxPacketRate.toFixed(1) }} / ↑
                  {{ record.txPacketRate.toFixed(1) }}
                </DescriptionsItem>
                <DescriptionsItem label="错误（收 / 发）">
                  {{ record.rxErrors }} / {{ record.txErrors }}
                </DescriptionsItem>
                <DescriptionsItem label="丢弃（收 / 发）">
                  {{ record.rxDropped }} / {{ record.txDropped }}
                </DescriptionsItem>
                <DescriptionsItem
                  :span="2"
                  label="全部 IPv6 地址"
                >
                  <div class="break-all whitespace-pre-line">
                    {{ ipv6Text(record.ipv6List) }}
                  </div>
                </DescriptionsItem>
                <DescriptionsItem
                  :span="3"
                  label="全部 IPv4 地址"
                >
                  {{
                    record.ipv4List?.length
                      ? record.ipv4List.join(', ')
                      : '-'
                  }}
                </DescriptionsItem>
              </Descriptions>
            </template>
          </Table>
        </TabPane>

        <!-- Docker 虚拟网络 -->
        <TabPane key="docker">
          <template #tab>
            Docker 网络 ({{ netInfo?.dockerNetworks?.length ?? 0 }})
          </template>
          <Empty
            v-if="!netInfo?.dockerNetworks?.length"
            description="未检测到 Docker 虚拟网络（可能未安装 Docker 或未挂载 docker.sock）"
          />
          <Table
            v-else
            :data-source="netInfo?.dockerNetworks ?? []"
            :pagination="false"
            :scroll="{ x: 1100 }"
            row-key="id"
            size="small"
          >
            <Table.Column title="网络名">
              <template #default="{ record }">
                <span class="font-medium">{{ record.name }}</span>
                <div class="text-xs text-gray-400">{{ record.id }}</div>
              </template>
            </Table.Column>
            <Table.Column data-index="driver" title="驱动" :width="100" />
            <Table.Column data-index="scope" title="作用域" :width="100" />
            <Table.Column title="子网" :width="180">
              <template #default="{ record }">
                {{ record.subnet || '-' }}
              </template>
            </Table.Column>
            <Table.Column title="网关" :width="150">
              <template #default="{ record }">
                {{ record.gateway || '-' }}
              </template>
            </Table.Column>
            <Table.Column title="宿主网桥" :width="150">
              <template #default="{ record }">
                {{ record.bridgeName || '-' }}
              </template>
            </Table.Column>
            <Table.Column title="属性" :width="180">
              <template #default="{ record }">
                <Tag v-if="record.internal" color="red">内部</Tag>
                <Tag v-if="record.attachable" color="green">可挂载</Tag>
                <Tag v-if="record.ipv6Enabled" color="blue">IPv6</Tag>
                <span
                  v-if="!record.internal && !record.attachable && !record.ipv6Enabled"
                  class="text-xs text-gray-400"
                >
                  -
                </span>
              </template>
            </Table.Column>
            <Table.Column title="容器数" :width="90">
              <template #default="{ record }">
                {{ record.containerCount }}
              </template>
            </Table.Column>

            <template #expandedRowRender="{ record }">
              <div
                v-if="!record.containers?.length"
                class="py-4 text-center text-xs text-gray-400"
              >
                该网络暂无容器接入。
              </div>
              <Table
                v-else
                :data-source="record.containers"
                :pagination="false"
                row-key="containerId"
                size="small"
              >
                <Table.Column data-index="containerName" title="容器名" />
                <Table.Column data-index="containerId" title="容器 ID" />
                <Table.Column data-index="ipv4" title="网络内 IPv4" />
                <Table.Column data-index="mac" title="端点 MAC" />
              </Table>
            </template>
          </Table>
        </TabPane>

        <!-- 汇总 -->
        <TabPane key="summary" tab="汇总">
          <Row :gutter="[12, 12]">
            <Col :lg="4" :md="8" :xs="12">
              <Statistic title="接口总数" :value="netSummary?.total ?? 0" />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic title="已启用接口" :value="netSummary?.up ?? 0" />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic title="物理网卡" :value="netSummary?.physical ?? 0" />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic title="网桥" :value="netSummary?.bridge ?? 0" />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic title="容器虚拟网卡" :value="netSummary?.veth ?? 0" />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic
                title="Docker 相关接口"
                :value="netSummary?.dockerRelated ?? 0"
              />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic
                title="Docker 网络"
                :value="netSummary?.dockerNetworks ?? 0"
              />
            </Col>
            <Col :lg="4" :md="8" :xs="12">
              <Statistic
                title="接入容器"
                :value="netSummary?.dockerContainers ?? 0"
              />
            </Col>
            <Col :lg="8" :md="8" :xs="24">
              <Statistic
                title="实时接收速率合计"
                :value="formatRateKb(netSummary?.rxRate ?? 0)"
              />
            </Col>
            <Col :lg="8" :md="8" :xs="24">
              <Statistic
                title="实时发送速率合计"
                :value="formatRateKb(netSummary?.txRate ?? 0)"
              />
            </Col>
          </Row>

          <Descriptions class="mt-3" :column="2" bordered size="small">
            <DescriptionsItem label="默认网关">
              {{ netSummary?.defaultGateway || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="默认出口网卡">
              {{ netSummary?.defaultInterface || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="累计接收">
              {{ formatBytes(netSummary?.totalRxBytes ?? 0) }}
            </DescriptionsItem>
            <DescriptionsItem label="累计发送">
              {{ formatBytes(netSummary?.totalTxBytes ?? 0) }}
            </DescriptionsItem>
            <DescriptionsItem label="采集时间">
              {{ netUpdatedText || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="采样间隔">
              {{ NET_POLL_MS / 1000 }} 秒
            </DescriptionsItem>
          </Descriptions>
        </TabPane>
      </Tabs>
    </Card>
  </div>
</template>
