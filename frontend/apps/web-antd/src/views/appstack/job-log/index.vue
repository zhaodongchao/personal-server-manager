<script lang="ts" setup>
/**
 * 定时任务日志（应用栈）。
 *
 * <p>这一页的核心使命是<b>区分两类失败</b>：派发失败（trigger 段）与执行失败（handle 段）。
 * 前者意味着「任务根本没跑起来」—— 执行器不可达、任务被并发阻塞策略丢弃；后者意味着
 * 「跑起来了但结果不对」—— 命令非零退出、HTTP 状态码不符、服务动作失败。把两者混成
 * 一个「失败」，排查时会直接走错方向。
 *
 * <p>执行中的日志支持自动轮询：定时任务的日志天生是「刚派发时还没有结果」，手动反复
 * 刷新是老式后台最烦人的体验。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { JobLogApi } from '#/api';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  DatePicker,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  clearJobLogApi,
  getJobLogPageApi,
  getJobLogStatisticsApi,
} from '#/api';

import LogDetailDrawer from './components/LogDetailDrawer.vue';

defineOptions({ name: 'AppstackJobLog' });

const { hasAccessByCodes } = useAccess();

const HANDLER_META: Record<string, { color: string; label: string }> = {
  HTTP: { color: 'blue', label: 'HTTP 接口' },
  INTERNAL: { color: 'green', label: '面板内置' },
  SERVICE: { color: 'orange', label: 'systemd 服务' },
  SHELL: { color: 'purple', label: '宿主 Shell' },
};

const STATUS_META: Record<string, { color: string; label: string }> = {
  DISCARDED: { color: 'default', label: '已丢弃' },
  FAILED: { color: 'error', label: '失败' },
  KILLED: { color: 'volcano', label: '已终止' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  TIMEOUT: { color: 'warning', label: '超时' },
};

function handlerMeta(type?: string) {
  return HANDLER_META[type ?? ''] ?? { color: 'default', label: type ?? '-' };
}

function statusMeta(status?: string) {
  return STATUS_META[status ?? ''] ?? { color: 'default', label: status ?? '-' };
}

const loading = ref(false);
const list = ref<JobLogApi.JobLog[]>([]);
const pagination = reactive({ current: 1, pageSize: 20, total: 0 });
const stats = ref<JobLogApi.Statistics>();

const jobName = ref('');
const statusFilter = ref<string>();
const handlerFilter = ref<string>();
const triggerFilter = ref<string>();
const range = ref<any>(null);

const statusOptions = [
  { label: '执行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '超时', value: 'TIMEOUT' },
  { label: '已丢弃', value: 'DISCARDED' },
  { label: '已终止', value: 'KILLED' },
];

const handlerOptions = [
  { label: '宿主 Shell', value: 'SHELL' },
  { label: 'HTTP 接口', value: 'HTTP' },
  { label: 'systemd 服务', value: 'SERVICE' },
  { label: '面板内置', value: 'INTERNAL' },
];

const triggerOptions = [
  { label: '定时触发', value: 'CRON' },
  { label: '手动触发', value: 'MANUAL' },
];

const autoRefresh = ref(false);
let timer: any = null;

const hasRunning = computed(() =>
  list.value.some((item) => item.status === 'RUNNING'),
);

async function load() {
  loading.value = true;
  try {
    const res = await getJobLogPageApi({
      beginTime: range.value?.[0] ? range.value[0].format('YYYY-MM-DDTHH:mm:ss') : undefined,
      endTime: range.value?.[1] ? range.value[1].format('YYYY-MM-DDTHH:mm:ss') : undefined,
      handler: handlerFilter.value || undefined,
      jobName: jobName.value || undefined,
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      status: statusFilter.value || undefined,
      triggerType: triggerFilter.value || undefined,
    });
    list.value = res?.records ?? [];
    pagination.total = res?.total ?? 0;
  } catch {
    list.value = [];
    pagination.total = 0;
  } finally {
    loading.value = false;
  }
}

async function loadStats() {
  try {
    stats.value = await getJobLogStatisticsApi();
  } catch {
    stats.value = undefined;
  }
}

function refreshAll() {
  void load();
  void loadStats();
}

function search() {
  pagination.current = 1;
  refreshAll();
}

function resetFilters() {
  jobName.value = '';
  statusFilter.value = undefined;
  handlerFilter.value = undefined;
  triggerFilter.value = undefined;
  range.value = null;
  search();
}

function toggleAutoRefresh(checked: boolean) {
  autoRefresh.value = checked;
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  if (checked) {
    timer = setInterval(() => {
      if (!autoRefresh.value) return;
      if (document.visibilityState !== 'visible') return;
      void load();
      void loadStats();
    }, 5000);
  }
}

function formatTime(value?: string) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 19);
}

// ==================== 详情抽屉 ====================

const detailOpen = ref(false);
const detailId = ref<null | string>(null);

function openDetail(record: JobLogApi.JobLog) {
  detailId.value = record.id ?? null;
  detailOpen.value = true;
}

// ==================== 清理 ====================

const clearOpen = ref(false);
const clearSaving = ref(false);
const clearForm = reactive({
  beforeDays: 30,
  confirm: '',
  status: undefined as string | undefined,
});

function openClear() {
  clearForm.beforeDays = stats.value?.retentionDays ?? 30;
  clearForm.confirm = '';
  clearForm.status = undefined;
  clearOpen.value = true;
}

async function doClear() {
  if (clearForm.confirm.trim() !== 'CLEAR LOG') {
    message.error('请输入确认关键字 CLEAR LOG');
    return;
  }
  const before = new Date(
    Date.now() - clearForm.beforeDays * 24 * 60 * 60 * 1000,
  );
  const pad = (n: number) => String(n).padStart(2, '0');
  const beforeTime = `${before.getFullYear()}-${pad(before.getMonth() + 1)}-${pad(
    before.getDate(),
  )}T${pad(before.getHours())}:${pad(before.getMinutes())}:00`;
  clearSaving.value = true;
  try {
    const res = await clearJobLogApi({
      beforeTime,
      confirm: clearForm.confirm.trim(),
      status: clearForm.status,
    });
    message.success(`已清理 ${res?.deleted ?? 0} 条日志`);
    clearOpen.value = false;
    refreshAll();
  } catch {
    // 错误提示由请求拦截器统一给出
  } finally {
    clearSaving.value = false;
  }
}

onMounted(() => {
  refreshAll();
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <Page class="flex flex-col">
    <div class="mb-3 grid grid-cols-3 gap-2 md:grid-cols-6">
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">日志总数</div>
        <div class="text-lg font-semibold">{{ stats?.total ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">成功</div>
        <div class="text-lg font-semibold text-green-600">{{ stats?.success ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">失败 / 超时</div>
        <div
          class="text-lg font-semibold"
          :class="(stats?.failed ?? 0) > 0 ? 'text-red-600' : ''"
        >
          {{ stats?.failed ?? '-' }}
        </div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">执行中</div>
        <div class="text-lg font-semibold text-blue-600">{{ stats?.running ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">已丢弃 / 终止</div>
        <div class="text-lg font-semibold">
          {{ (stats?.discarded ?? 0) + (stats?.killed ?? 0) || '-' }}
        </div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">平均耗时</div>
        <div class="text-lg font-semibold">
          {{ stats?.avgDurationMs ? `${stats.avgDurationMs} ms` : '-' }}
        </div>
      </div>
    </div>

    <Alert
      v-if="hasRunning"
      class="mb-3"
      show-icon
      type="info"
      message="存在「执行中」的日志"
      description="执行中的日志是被派发时先落库的，结果由执行线程回填；打开「自动刷新」即可看到结果落定。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="jobName"
        allow-clear
        class="w-48"
        placeholder="按任务名过滤"
        @press-enter="search"
      />
      <Select
        v-model:value="statusFilter"
        :options="statusOptions"
        allow-clear
        class="w-32"
        placeholder="状态"
      />
      <Select
        v-model:value="handlerFilter"
        :options="handlerOptions"
        allow-clear
        class="w-40"
        placeholder="处理器"
      />
      <Select
        v-model:value="triggerFilter"
        :options="triggerOptions"
        allow-clear
        class="w-32"
        placeholder="触发方式"
      />
      <DatePicker.RangePicker
        v-model:value="range"
        :show-time="{ format: 'HH:mm' }"
        class="w-80"
        format="YYYY-MM-DD HH:mm"
      />
      <Button type="primary" @click="search">搜索</Button>
      <Button @click="resetFilters">重置</Button>
      <Space class="ml-auto">
        <span class="text-xs text-gray-500">自动刷新（5s）</span>
        <Switch
          :checked="autoRefresh"
          size="small"
          @change="(v: any) => toggleAutoRefresh(!!v)"
        />
        <Button
          v-if="hasAccessByCodes(['appstack:joblog:clear'])"
          danger
          @click="openClear"
        >
          清理日志
        </Button>
      </Space>
    </div>

    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Table
        :data-source="list"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          total: pagination.total,
          onChange: (page: number, size: number) => {
            pagination.current = page;
            pagination.pageSize = size;
            load();
          },
        }"
        :row-key="(record: JobLogApi.JobLog) => record.id"
        :scroll="{ x: 1600 }"
        size="small"
      >
        <Table.Column key="triggerTime" title="触发时间" :width="170" fixed="left">
          <template #default="{ record }">
            <span class="text-xs">{{ formatTime(record.triggerTime) }}</span>
          </template>
        </Table.Column>
        <Table.Column key="jobName" title="任务" :width="200">
          <template #default="{ record }">
            <div>{{ record.jobName || '-' }}</div>
            <div class="font-mono text-xs text-gray-500">
              {{ record.executorAppName || '-' }}
            </div>
          </template>
        </Table.Column>
        <Table.Column key="handler" title="处理器" :width="120">
          <template #default="{ record }">
            <Tag :color="handlerMeta(record.handler).color">
              {{ handlerMeta(record.handler).label }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="triggerType" title="触发方式" :width="100">
          <template #default="{ record }">
            <Tag :color="record.triggerType === 'MANUAL' ? 'geekblue' : 'default'">
              {{ record.triggerType === 'MANUAL' ? '手动' : '定时' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="status" title="状态" :width="100">
          <template #default="{ record }">
            <Tag :color="statusMeta(record.status).color">
              {{ statusMeta(record.status).label }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="triggerCode" title="派发" :width="180">
          <template #default="{ record }">
            <Tag :color="record.triggerCode === 0 ? 'success' : 'error'">
              {{ record.triggerCode === 0 ? '已派发' : `失败 ${record.triggerCode}` }}
            </Tag>
            <span v-if="record.triggerMsg" class="text-xs text-red-500">
              {{ record.triggerMsg }}
            </span>
          </template>
        </Table.Column>
        <Table.Column key="handleMsg" title="执行结果" ellipsis>
          <template #default="{ record }">
            <span
              class="text-xs"
              :class="record.handleCode === 0 ? 'text-gray-600' : 'text-red-600'"
            >
              {{ record.handleMsg || '-' }}
            </span>
          </template>
        </Table.Column>
        <Table.Column key="handleDurationMs" title="耗时" :width="90">
          <template #default="{ record }">
            <span class="text-xs">
              {{ record.handleDurationMs === null || record.handleDurationMs === undefined
                ? '-'
                : `${record.handleDurationMs} ms` }}
            </span>
          </template>
        </Table.Column>
        <Table.Column key="retryIndex" title="重试" :width="70">
          <template #default="{ record }">
            <Tag v-if="(record.retryIndex ?? 0) > 0" color="orange">
              #{{ record.retryIndex }}
            </Tag>
            <span v-else class="text-xs text-gray-400">-</span>
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" :width="80" fixed="right">
          <template #default="{ record }">
            <Button size="small" type="link" @click="openDetail(record as JobLogApi.JobLog)">
              详情
            </Button>
          </template>
        </Table.Column>
      </Table>
    </div>

    <LogDetailDrawer v-model:open="detailOpen" :log-id="detailId" />

    <Modal
      v-model:open="clearOpen"
      :confirm-loading="clearSaving"
      ok-text="执行清理"
      :ok-button-props="{ danger: true }"
      title="清理调度日志"
      @ok="doClear"
    >
      <Alert
        class="mb-3"
        show-icon
        type="warning"
        message="清理不可逆"
        description="面板不提供「无条件清空全表」的入口：至少要给出一个条件。清理动作会记入审计日志。"
      />
      <div class="space-y-3">
        <div>
          <div class="mb-1 text-sm">保留最近多少天</div>
          <InputNumber v-model:value="clearForm.beforeDays" :max="3650" :min="0" class="w-full" />
          <div class="mt-1 text-xs text-gray-500">
            将删除触发时间早于「今天 - {{ clearForm.beforeDays }} 天」的日志。
          </div>
        </div>
        <div>
          <div class="mb-1 text-sm">仅清理指定状态（可选）</div>
          <Select
            v-model:value="clearForm.status"
            :options="statusOptions"
            allow-clear
            class="w-full"
            placeholder="不限状态"
          />
        </div>
        <div>
          <div class="mb-1 text-sm">
            确认关键字：<span class="font-mono font-semibold text-red-600">CLEAR LOG</span>
          </div>
          <Input v-model:value="clearForm.confirm" class="font-mono" placeholder="CLEAR LOG" />
        </div>
      </div>
    </Modal>
  </Page>
</template>
