<script lang="ts" setup>
/**
 * 服务详情抽屉
 *
 * 展示四块内容：① 基本信息 ② 运行指标 ③ 依赖关系 ④ 命令原文（status / cat）。
 * 抽屉本身只做展示与事件上抛，所有写操作（含高危二次确认）统一由列表页处理，
 * 避免两处各写一套确认逻辑而产生分叉。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Space,
  Spin,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';

import { getServiceDetailApi } from '#/api';

import ServiceLogPanel from './ServiceLogPanel.vue';

import {
  activeColor,
  activeLabel,
  fmtBytes,
  fmtCpuNanos,
  fmtDuration,
  fmtEpochMs,
  subLabel,
  unitFileStateColor,
  unitFileStateLabel,
} from '../utils';

defineOptions({ name: 'OpsServiceDetailDrawer' });

const props = defineProps<{
  open: boolean;
  name: string;
  initialTab?: string;
  /** 父层发生变化时递增，用于触发详情重载 */
  reloadKey?: number;
  /** 正在执行中的动作，格式 name:action */
  acting?: string;
  canManage?: boolean;
  canLog?: boolean;
}>();

const emit = defineEmits<{
  action: [{ name: string; action: string }];
  'update:open': [boolean];
}>();

const tab = ref('overview');
const loading = ref(false);
const detail = ref<OpsApi.ServiceDetail>();

const basic = computed(() => detail.value?.basic);

const REL_LABELS: Array<[string, string]> = [
  ['wantedBy', '被谁要求 (WantedBy)'],
  ['requiredBy', '被谁强依赖 (RequiredBy)'],
  ['requires', '强依赖 (Requires)'],
  ['after', '启动顺序 (After)'],
  ['dependencies', '依赖的单元'],
  ['dependents', '依赖本单元的单元'],
];

function relList(key: string): string[] {
  const source = detail.value as unknown as Record<string, string[]> | undefined;
  return source?.[key] ?? [];
}

async function load() {
  if (!props.name) {
    return;
  }
  loading.value = true;
  try {
    detail.value = await getServiceDetailApi(props.name);
  } catch {
    detail.value = undefined;
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.open, props.name, props.reloadKey],
  () => {
    if (props.open) {
      tab.value = props.initialTab || 'overview';
      void load();
    }
  },
  { immediate: true },
);

function close() {
  emit('update:open', false);
}

function fire(action: string) {
  if (!props.name) {
    return;
  }
  emit('action', { name: props.name, action });
}

async function copy(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text);
    message.success(`${label}已复制`);
  } catch {
    message.error('复制失败（浏览器未授予剪贴板权限）');
  }
}

</script>

<template>
  <Drawer
    :destroy-on-close="false"
    :open="open"
    :title="name"
    :width="820"
    @close="close"
  >
    <template #extra>
      <Space v-if="canManage" :size="4">
        <Button
          :loading="acting === `${name}:restart`"
          size="small"
          type="primary"
          @click="fire('restart')"
        >
          重启
        </Button>
        <Button
          :loading="acting === `${name}:stop`"
          danger
          size="small"
          @click="fire('stop')"
        >
          停止
        </Button>
        <Button
          :loading="acting === `${name}:start`"
          size="small"
          @click="fire('start')"
        >
          启动
        </Button>
        <Button
          :loading="acting === `${name}:reload`"
          size="small"
          @click="fire('reload')"
        >
          重载配置
        </Button>
        <Button
          :loading="acting === `${name}:reset-failed`"
          size="small"
          @click="fire('reset-failed')"
        >
          清除失败
        </Button>
      </Space>
    </template>

    <Spin :spinning="loading && !detail">
      <template v-if="detail && basic">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Tag :color="activeColor(basic.active)">
            {{ activeLabel(basic.active) }}<template v-if="basic.sub"> · {{ subLabel(basic.sub) }}</template>
          </Tag>
          <Tag :color="unitFileStateColor(basic.unitFileState)">
            自启：{{ unitFileStateLabel(basic.unitFileState) }}
          </Tag>
          <Tag v-if="basic.masked" color="red">已屏蔽</Tag>
          <Tag v-if="basic.alias" color="purple">别名单元</Tag>
          <Tag v-if="basic.protectedService" color="orange">保护清单</Tag>
          <span
            v-if="basic.failed && basic.failedSeconds"
            class="text-xs text-red-500"
          >
            已失败 {{ fmtDuration(basic.failedSeconds) }}
          </span>
        </div>

        <Alert
          v-if="detail.protectedService"
          :message="detail.protectionHint || '该服务承载面板/SSH 等关键路径，停止或屏蔽需二次确认'"
          class="mb-3"
          show-icon
          type="warning"
        />

        <Tabs v-model:active-key="tab" size="small">
          <Tabs.TabPane key="overview" tab="概览">
            <Descriptions :column="2" bordered size="small" title="基本信息">
              <Descriptions.Item label="单元名">{{ basic.name }}</Descriptions.Item>
              <Descriptions.Item label="描述">{{ basic.description || '-' }}</Descriptions.Item>
              <Descriptions.Item label="加载状态">{{ basic.load || '-' }}</Descriptions.Item>
              <Descriptions.Item label="运行状态">
                {{ activeLabel(basic.active) }} / {{ subLabel(basic.sub) }}
              </Descriptions.Item>
              <Descriptions.Item label="自启状态">
                {{ unitFileStateLabel(basic.unitFileState) }}
              </Descriptions.Item>
              <Descriptions.Item label="别名指向">{{ basic.aliasOf || '-' }}</Descriptions.Item>
              <Descriptions.Item label="单元文件" :span="2">
                <span class="break-all font-mono text-xs">{{ basic.fragmentPath || detail.fragmentPath || '-' }}</span>
              </Descriptions.Item>
              <Descriptions.Item v-if="detail.execStart" label="启动命令" :span="2">
                <span class="break-all font-mono text-xs">{{ detail.execStart }}</span>
              </Descriptions.Item>
            </Descriptions>

            <Descriptions :column="2" bordered class="mt-4" size="small" title="运行指标">
              <Descriptions.Item label="主进程 PID">{{ detail.mainPid ?? basic.mainPid ?? '-' }}</Descriptions.Item>
              <Descriptions.Item label="内存占用">{{ fmtBytes(detail.memoryBytes ?? basic.memoryBytes) }}</Descriptions.Item>
              <Descriptions.Item label="累计 CPU">{{ fmtCpuNanos(detail.cpuNanos) }}</Descriptions.Item>
              <Descriptions.Item label="已运行时长">{{ fmtDuration(detail.uptimeSeconds ?? basic.uptimeSeconds) }}</Descriptions.Item>
              <Descriptions.Item label="进入活动时刻">{{ detail.activeEnterTimestamp || '-' }}</Descriptions.Item>
              <Descriptions.Item label="重启次数">{{ detail.restartCount ?? basic.restartCount ?? 0 }}</Descriptions.Item>
              <Descriptions.Item label="状态变更时刻">{{ basic.stateChangeTimestamp || '-' }}</Descriptions.Item>
              <Descriptions.Item label="失败起始">{{ fmtEpochMs(basic.failedSinceEpoch) }}</Descriptions.Item>
            </Descriptions>

            <div class="mt-4">
              <div class="mb-2 text-sm font-medium">依赖关系</div>
              <template v-if="REL_LABELS.some(([k]) => relList(k).length > 0)">
                <div v-for="[key, label] in REL_LABELS" :key="key" class="mb-2">
                  <template v-if="relList(key).length > 0">
                    <div class="mb-1 text-xs text-gray-500">{{ label }}</div>
                    <div class="flex flex-wrap gap-1">
                      <Tag v-for="item in relList(key)" :key="item">{{ item }}</Tag>
                    </div>
                  </template>
                </div>
              </template>
              <Empty
                v-else
                :image="Empty.PRESENTED_IMAGE_SIMPLE"
                description="无依赖信息"
              />
            </div>
          </Tabs.TabPane>

          <Tabs.TabPane
            key="logs"
            :disabled="!canLog"
            :tab="`日志${canLog ? '' : '（无权限）'}`"
          >
            <div class="h-[62vh]">
              <ServiceLogPanel :active="open && tab === 'logs'" :name="name" />
            </div>
          </Tabs.TabPane>

          <Tabs.TabPane key="raw" tab="命令原文">
            <div class="mb-3">
              <div class="mb-1 flex items-center gap-2">
                <span class="text-sm font-medium">systemctl status</span>
                <Button size="small" type="link" @click="copy(detail.rawStatus || '', 'status 原文')">
                  复制
                </Button>
              </div>
              <pre class="max-h-[26vh] overflow-auto rounded border border-gray-200 bg-gray-50 p-2 font-mono text-[11px] leading-5 dark:border-gray-700 dark:bg-gray-900">{{ detail.rawStatus || '（空）' }}</pre>
            </div>
            <div>
              <div class="mb-1 flex items-center gap-2">
                <span class="text-sm font-medium">systemctl cat</span>
                <Button size="small" type="link" @click="copy(detail.unitFile || '', '单元文件原文')">
                  复制
                </Button>
              </div>
              <pre class="max-h-[26vh] overflow-auto rounded border border-gray-200 bg-gray-50 p-2 font-mono text-[11px] leading-5 dark:border-gray-700 dark:bg-gray-900">{{ detail.unitFile || '（空）' }}</pre>
            </div>
          </Tabs.TabPane>
        </Tabs>
      </template>
      <Empty v-else-if="!loading" description="未获取到服务详情" />
    </Spin>
  </Drawer>
</template>
