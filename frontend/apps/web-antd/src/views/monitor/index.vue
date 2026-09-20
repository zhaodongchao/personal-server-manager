<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { MonitorApi } from '#/api';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore } from '@vben/stores';

import {
  Card,
  Col,
  Descriptions,
  DescriptionsItem,
  Progress,
  Row,
  Statistic,
  TabPane,
  Table,
  Tabs,
} from 'ant-design-vue';

import { getMonitorHistoryApi, getMonitorOverviewApi } from '#/api';

defineOptions({ name: 'MonitorIndex' });

/** 滚动窗口保留帧数（5s/帧 ≈ 20 分钟） */
const MAX_POINTS = 240;

const overview = ref<MonitorApi.MonitorOverview>();
const frames = ref<MonitorApi.MetricFrame[]>([]);
const wsState = ref<'connected' | 'connecting' | 'offline'>('offline');

const perfChartRef = ref<EchartsUIType>();
const netChartRef = ref<EchartsUIType>();
const { renderEcharts: renderPerf, updateData: updatePerf } =
  useEcharts(perfChartRef);
const { renderEcharts: renderNet, updateData: updateNet } =
  useEcharts(netChartRef);

let ws: null | WebSocket = null;
let reconnectTimer: null | ReturnType<typeof setTimeout> = null;

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
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
  return (
    lvm.physicalVolumes.length > 0 ||
    lvm.volumeGroups.length > 0 ||
    lvm.logicalVolumes.length > 0
  );
});

/** 文件系统树节点 */
interface FsTreeNode {
  key: string;
  /** 展示名：根为 "/"，子节点为末段目录名 */
  name: string;
  fsType: string;
  totalBytes: number;
  usableBytes: number;
  usage: number;
  children: FsTreeNode[];
}

/** 计算挂载点在文件系统树中的父挂载点（最长字面前缀），无则返回 null */
function parentMount(mount: string, disks: MonitorApi.DiskInfo[]): string | null {
  let best: string | null = null;
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
      fsType: d.fsType,
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
    arr.sort((a, b) => (a.name === '/') - (b.name === '/') || a.name.localeCompare(b.name));
    arr.forEach((n) => sortRec(n.children));
  };
  sortRec(roots);
  return roots;
});

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

    <!-- 系统信息 / 磁盘 / 网卡 -->
    <Row :gutter="[12, 12]" class="mt-3">
      <Col :lg="8" :xs="24">
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
      <Col :lg="8" :xs="24">
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
                    <Table.Column data-index="type" title="类型" />
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
                <Table.Column title="文件系统">
                  <template #default="{ record }">
                    {{ record.fsType || '-' }}
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
      <Col :lg="8" :xs="24">
        <Card title="网络接口">
          <Table
            :data-source="overview?.interfaces ?? []"
            :pagination="false"
            row-key="name"
            size="small"
          >
            <Table.Column data-index="name" title="接口" />
            <Table.Column data-index="ipv4" title="IPv4" />
            <Table.Column title="速率">
              <template #default="{ record }">
                {{ record.speed > 0 ? `${record.speed} Mbps` : '-' }}
              </template>
            </Table.Column>
          </Table>
        </Card>
      </Col>
    </Row>
  </div>
</template>
