<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { DashboardApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { IconifyIcon } from '@vben/icons';

import {
  Card,
  Col,
  Empty,
  Progress,
  Row,
  Skeleton,
  Tag,
  Timeline,
  TimelineItem,
} from 'ant-design-vue';

import { getQuickServicesApi, getSshLoginsApi, getVisitSourcesApi } from '#/api';
import { quickNavHostLabel, quickNavUrl } from '#/utils/quick-nav';

defineOptions({ name: 'DashboardWorkspace' });

const services = ref<DashboardApi.QuickService[]>([]);
const sshLogins = ref<DashboardApi.SshLogin[]>([]);
const visitSources = ref<DashboardApi.VisitSource[]>([]);
const loading = ref(true);

/** 服务展示名 -> 图标（默认 app-window） */
const SERVICE_ICONS: Record<string, string> = {
  GitLab: 'lucide:gitlab',
  Jenkins: 'lucide:hammer',
  Jellyfin: 'lucide:tv',
  Nginx: 'lucide:server',
  Apache: 'lucide:globe',
  Portainer: 'lucide:container',
  MinIO: 'lucide:database',
  Nextcloud: 'lucide:cloud',
  Emby: 'lucide:play',
  Plex: 'lucide:monitor-play',
  qBittorrent: 'lucide:download',
  Sonarr: 'lucide:film',
  Radarr: 'lucide:film',
  Prowlarr: 'lucide:search',
  Transmission: 'lucide:download',
};
const DEFAULT_ICON = 'lucide:app-window';

/** 访问来源饼图配色 */
const SOURCE_COLORS = [
  '#5ab1ef',
  '#019680',
  '#5470c6',
  '#ee6666',
  '#fac858',
  '#73c0de',
  '#3ba272',
  '#fc8452',
  '#9a60b4',
  '#ea7ccc',
];

const visitTotal = computed(() =>
  visitSources.value.reduce((sum, item) => sum + item.count, 0),
);

const sourceChartRef = ref<EchartsUIType>();
const { renderEcharts: renderSource } = useEcharts(sourceChartRef);

function serviceIcon(service: DashboardApi.QuickService): string {
  return service.icon || SERVICE_ICONS[service.displayName] || DEFAULT_ICON;
}

/**
 * 服务访问地址：协议与主机由导航配置决定（域名留空时跟随面板当前域名），
 * 规则与管理页列表共用 utils/quick-nav.ts，避免两处各拼一套。
 */
function serviceUrl(service: DashboardApi.QuickService): string {
  return quickNavUrl(service);
}

function renderSourceChart() {
  renderSource({
    color: SOURCE_COLORS,
    tooltip: {
      formatter: '{b}<br/>{c} 次（{d}%）',
      trigger: 'item',
    },
    legend: { bottom: 0, left: 'center', type: 'scroll' },
    series: [
      {
        avoidLabelOverlap: true,
        center: ['50%', '42%'],
        data: visitSources.value.map((item) => ({
          name: item.region,
          value: item.count,
        })),
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' },
          label: { fontWeight: 'bold', show: true },
        },
        itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 6 },
        label: { show: false },
        radius: ['38%', '62%'],
        type: 'pie',
      },
    ],
  });
}

onMounted(async () => {
  try {
    const [serviceRes, sshRes, sourceRes] = await Promise.all([
      getQuickServicesApi(),
      getSshLoginsApi(),
      getVisitSourcesApi(),
    ]);
    services.value = serviceRes;
    sshLogins.value = sshRes;
    visitSources.value = sourceRes;
    renderSourceChart();
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="p-4 md:p-5">
    <!-- 快捷导航 -->
    <Card title="快捷导航" :bordered="false" class="mb-3">
      <Skeleton v-if="loading" :paragraph="{ rows: 4 }" active />
      <Empty v-else-if="services.length === 0" description="暂无运行中的已知服务" />
      <div v-else class="grid grid-cols-2 gap-3 md:grid-cols-4">
        <a
          v-for="service in services"
          :key="service.name"
          :href="serviceUrl(service)"
          target="_blank"
          rel="noopener"
          class="group flex flex-col items-center gap-2 rounded-lg border border-gray-100 bg-gray-50/60 px-3 py-4 transition-all hover:border-primary hover:bg-primary/5 hover:shadow-sm"
        >
          <div
            class="flex h-11 w-11 items-center justify-center rounded-full bg-primary/10 text-xl text-primary transition-transform group-hover:scale-110"
          >
            <IconifyIcon :icon="serviceIcon(service)" />
          </div>
          <div class="text-sm font-medium">{{ service.displayName }}</div>
          <div class="flex items-center gap-1 text-xs text-gray-400">
            <span
              class="inline-block h-1.5 w-1.5 rounded-full"
              :class="service.running ? 'bg-green-500' : 'bg-gray-300'"
            />
            {{ quickNavHostLabel(service) }}
          </div>
        </a>
      </div>
    </Card>

    <Row :gutter="[12, 12]">
      <!-- 最新动态 -->
      <Col :lg="16" :xs="24">
        <Card title="最新动态" :bordered="false" class="h-full">
          <Skeleton v-if="loading" :paragraph="{ rows: 8 }" active />
          <Empty v-else-if="sshLogins.length === 0" description="暂无 SSH 登录记录" />
          <Timeline v-else>
            <TimelineItem
              v-for="(login, index) in sshLogins"
              :key="`${login.time}-${index}`"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="text-sm">
                  <span class="font-medium">{{ login.username }}</span>
                  <span class="text-gray-400"> 通过 SSH 从 </span>
                  <span class="font-mono text-xs">{{ login.ip }}:{{ login.port }}</span>
                  <span class="text-gray-400"> 登录成功</span>
                </div>
                <div class="flex items-center gap-2">
                  <Tag :color="login.method === 'publickey' ? 'green' : 'orange'">
                    {{ login.method === 'publickey' ? '密钥' : '密码' }}
                  </Tag>
                  <span class="text-xs text-gray-400">{{ login.time }}</span>
                </div>
              </div>
            </TimelineItem>
          </Timeline>
        </Card>
      </Col>

      <!-- 访问来源 -->
      <Col :lg="8" :xs="24">
        <Card title="访问来源" :bordered="false" class="h-full">
          <Skeleton v-if="loading" :paragraph="{ rows: 6 }" active />
          <Empty v-else-if="visitSources.length === 0" description="暂无访问数据" />
          <template v-else>
            <EchartsUI ref="sourceChartRef" height="240px" />
            <div class="mt-3 space-y-2">
              <div
                v-for="(item, index) in visitSources"
                :key="item.region"
                class="flex items-center gap-2 text-sm"
              >
                <span
                  class="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                  :style="{ backgroundColor: SOURCE_COLORS[index % SOURCE_COLORS.length] }"
                />
                <span class="w-28 truncate text-gray-600">{{ item.region }}</span>
                <Progress
                  :percent="
                    visitTotal > 0
                      ? Number(((item.count / visitTotal) * 100).toFixed(1))
                      : 0
                  "
                  :show-info="false"
                  size="small"
                  class="flex-1"
                />
                <span class="text-xs text-gray-400">{{ item.count }} 次</span>
              </div>
            </div>
          </template>
        </Card>
      </Col>
    </Row>
  </div>
</template>
