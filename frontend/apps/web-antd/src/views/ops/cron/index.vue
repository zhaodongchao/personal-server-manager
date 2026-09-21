<script lang="ts" setup>
/**
 * 计划管理（定时任务）页面
 *
 * 重构要点：
 * 1. 命令经**宿主执行通道**在宿主机上运行（容器内没有 systemctl/ufw/df 等命令），
 *    通道不可用时整页只读降级并给出安装指引——过去「点了执行却必然失败」的根源在此。
 * 2. 列表统一用 VXE-Table（与用户管理、服务管理页一致），支持分页/多选/批量。
 * 3. 启用开关直接生效（PATCH /status），不必进表单改完再保存。
 * 4. 手动执行后自动跟随本次日志（轮询单条直到 finishedAt 非空），不再靠手刷。
 * 5. 统计卡可点：点「近 24h 失败」直接按失败筛选列表。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { OpsApi } from '#/api';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, Popconfirm, Space, Switch, Tag, message } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  batchDeleteCronJobApi,
  createCronJobApi,
  deleteCronJobApi,
  getCronJobPageApi,
  getCronSummaryApi,
  getHostCapabilityApi,
  runCronJobApi,
  setCronJobStatusApi,
  updateCronJobApi,
} from '#/api';

import CronDetailDrawer from './components/CronDetailDrawer.vue';
import CronFormDrawer from './components/CronFormDrawer.vue';
import CronLogDrawer from './components/CronLogDrawer.vue';
import HostChannelBanner from '../components/HostChannelBanner.vue';

import {
  RESULT_OPTIONS,
  fmtCountdown,
  fmtTime,
  resultColor,
  resultLabel,
} from './utils';

defineOptions({ name: 'OpsCron' });

const { hasAccessByCodes } = useAccess();

const canAdd = computed(() => hasAccessByCodes(['ops:cron:add']));
const canEdit = computed(() => hasAccessByCodes(['ops:cron:edit']));
const canDelete = computed(() => hasAccessByCodes(['ops:cron:delete']));
const canRun = computed(() => hasAccessByCodes(['ops:cron:run']));
const canStatus = computed(() => hasAccessByCodes(['ops:cron:status']));
const canClean = computed(() => hasAccessByCodes(['ops:cron:log-clean']));

// ==================== 宿主通道 ====================

const capability = ref<OpsApi.HostCapability>();
const channelOk = computed(
  () => !!capability.value?.ok && (capability.value?.protocol ?? 0) >= 1,
);
const writable = computed(() => channelOk.value);

async function loadCapability() {
  try {
    capability.value = await getHostCapabilityApi();
  } catch {
    capability.value = undefined;
  }
}

// ==================== 统计 ====================

const summary = ref<OpsApi.CronSummary>();
/** 点统计卡设置的快捷筛选 */
const quickResult = ref<string>();

async function loadSummary() {
  try {
    summary.value = await getCronSummaryApi();
  } catch {
    summary.value = undefined;
  }
}

const statCards = computed(() => [
  { key: 'total', label: '任务总数', value: summary.value?.total ?? 0, color: '#1677ff' },
  { key: 'enabled', label: '已启用', value: summary.value?.enabled ?? 0, color: '#52c41a' },
  { key: 'disabled', label: '已停用', value: summary.value?.disabled ?? 0, color: '#8c8c8c' },
  { key: 'running', label: '执行中', value: summary.value?.running ?? 0, color: '#fa8c16' },
  {
    key: 'failed24h',
    label: '近 24h 失败',
    value: summary.value?.failed24h ?? 0,
    color: '#ff4d4f',
    clickable: true,
  },
]);

function onStatClick(key: string) {
  if (key !== 'failed24h') return;
  quickResult.value = quickResult.value === 'fail' ? undefined : 'fail';
  gridApi.query();
}

// ==================== 列表 ====================

const STATUS_OPTIONS = [
  { label: '已启用', value: 1 },
  { label: '已停用', value: 0 },
];

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '任务名或命令' },
      fieldName: 'keyword',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: STATUS_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'status',
      label: '状态',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: RESULT_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'lastResult',
      label: '最近结果',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  checkboxConfig: { highlight: true, reserve: true },
  columns: [
    { type: 'checkbox', width: 42 },
    { title: '序号', type: 'seq', width: 56 },
    { field: 'name', minWidth: 160, slots: { default: 'name' }, title: '任务名' },
    { field: 'cronExpr', minWidth: 180, slots: { default: 'cron' }, title: '调度' },
    { field: 'command', minWidth: 200, slots: { default: 'command' }, title: '命令' },
    { field: 'status', slots: { default: 'status' }, title: '启用', width: 90 },
    { field: 'lastRunAt', slots: { default: 'lastRun' }, title: '上次执行', width: 190 },
    { field: 'nextRunAt', slots: { default: 'nextRun' }, title: '下次执行', width: 190 },
    { field: 'running', slots: { default: 'running' }, title: '运行态', width: 86 },
    {
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: '操作',
      width: 260,
    },
  ],
  pagerConfig: { pageSize: 20, pageSizes: [20, 50, 100] },
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const params: Parameters<typeof getCronJobPageApi>[0] = {
          ...(formValues as Record<string, unknown>),
          pageNum: page.currentPage,
          pageSize: page.pageSize,
        };
        // 统计卡快捷筛选优先级高于表单
        if (quickResult.value) {
          params.lastResult = quickResult.value;
        }
        const res = await getCronJobPageApi(params);
        return { items: res?.records ?? [], total: res?.total ?? 0 };
      },
    },
  },
  rowConfig: { keyField: 'id' },
};

const [Grid, gridApi] = useVbenVxeGrid({ formOptions, gridOptions });

async function reloadAll() {
  gridApi.query();
  await loadSummary();
}

/** 倒计时用的响应式时钟（30s 一跳，让下次执行的倒计时自动刷新） */
const tick = ref(Date.now());
let tickTimer: ReturnType<typeof setInterval> | undefined;

onMounted(() => {
  void loadCapability();
  void reloadAll();
  tickTimer = setInterval(() => {
    tick.value = Date.now();
  }, 30_000);
});

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer);
});

// ==================== 选择 ====================

const selected = ref<string[]>([]);

function syncSelection() {
  const rows = (gridApi.grid?.getCheckboxRecords?.() ?? []) as OpsApi.CronJob[];
  selected.value = rows.map((r) => String(r.id));
}

function clearSelection() {
  gridApi.grid?.clearCheckboxRow?.();
  selected.value = [];
}

// ==================== 动作 ====================

const acting = ref('');

async function onToggleStatus(row: OpsApi.CronJob, checked: boolean) {
  if (!row.id) return;
  acting.value = `status-${row.id}`;
  try {
    await setCronJobStatusApi(String(row.id), checked ? 1 : 0);
    message.success(checked ? '已启用' : '已停用');
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '操作失败');
  } finally {
    acting.value = '';
  }
}

async function onRun(row: OpsApi.CronJob) {
  acting.value = `run-${row.id}`;
  try {
    await runCronJobApi(String(row.id));
    message.success('已触发执行');
    openLog(row);
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '触发执行失败');
  } finally {
    acting.value = '';
  }
}

async function onDelete(row: OpsApi.CronJob) {
  if (!row.id) return;
  await deleteCronJobApi(String(row.id));
  message.success('已删除');
  await reloadAll();
}

async function onBatchDelete() {
  if (!selected.value.length) {
    message.warning('请先选择任务');
    return;
  }
  await batchDeleteCronJobApi(selected.value);
  message.success(`已删除 ${selected.value.length} 个任务`);
  clearSelection();
  await reloadAll();
}

async function onBatchStatus(status: number) {
  if (!selected.value.length) {
    message.warning('请先选择任务');
    return;
  }
  for (const id of selected.value) {
    await setCronJobStatusApi(id, status).catch(() => undefined);
  }
  message.success(`已${status === 1 ? '启用' : '停用'} ${selected.value.length} 个任务`);
  clearSelection();
  await reloadAll();
}

// ==================== 抽屉 ====================

const formOpen = ref(false);
const editing = ref<OpsApi.CronJob | null>(null);

function openCreate() {
  editing.value = null;
  formOpen.value = true;
}

function openEdit(row: OpsApi.CronJob) {
  editing.value = { ...row };
  formOpen.value = true;
}

function openCopy(row: OpsApi.CronJob) {
  editing.value = { ...row, id: undefined, name: `${row.name} - 副本` };
  formOpen.value = true;
}

async function onSubmit(payload: OpsApi.CronJob) {
  if (payload.id) {
    await updateCronJobApi(payload);
    message.success('已保存');
  } else {
    await createCronJobApi(payload);
    message.success('已创建');
  }
  formOpen.value = false;
  await reloadAll();
}

const logOpen = ref(false);
const logJob = ref<OpsApi.CronJob | null>(null);

function openLog(row: OpsApi.CronJob) {
  logJob.value = row;
  logOpen.value = true;
}

const detailOpen = ref(false);
const detailJob = ref<OpsApi.CronJob | null>(null);

function openDetail(row: OpsApi.CronJob) {
  detailJob.value = row;
  detailOpen.value = true;
}
</script>

<template>
  <Page auto-content-height>
    <HostChannelBanner :capability="capability" @refreshed="loadCapability" />

    <div class="mb-3 grid grid-cols-2 gap-3 md:grid-cols-5">
      <div
        v-for="card in statCards"
        :key="card.key"
        class="rounded border border-gray-200 bg-white px-4 py-3"
        :class="card.clickable ? 'cursor-pointer hover:border-blue-400' : ''"
        @click="onStatClick(card.key)"
      >
        <div class="text-xs text-gray-500">{{ card.label }}</div>
        <div class="mt-1 text-2xl font-semibold" :style="{ color: card.color }">
          {{ card.value }}
        </div>
      </div>
    </div>

    <Grid @checkbox-change="syncSelection" @checkbox-all="syncSelection">
      <template #toolbar-actions>
        <Space>
          <Button v-if="canAdd" type="primary" :disabled="!writable" @click="openCreate">
            新增任务
          </Button>
          <Button
            v-if="canStatus"
            :disabled="!selected.length || !writable"
            @click="onBatchStatus(1)"
          >
            批量启用
          </Button>
          <Button
            v-if="canStatus"
            :disabled="!selected.length || !writable"
            @click="onBatchStatus(0)"
          >
            批量停用
          </Button>
          <Popconfirm
            v-if="canDelete"
            title="确认删除选中的任务？"
            ok-text="删除"
            cancel-text="取消"
            @confirm="onBatchDelete"
          >
            <Button danger :disabled="!selected.length">批量删除</Button>
          </Popconfirm>
        </Space>
      </template>

      <template #name="{ row }">
        <a class="text-blue-600" @click="openDetail(row as OpsApi.CronJob)">
          {{ (row as OpsApi.CronJob).name }}
        </a>
      </template>

      <template #cron="{ row }">
        <div class="font-mono text-xs">{{ (row as OpsApi.CronJob).cronExpr }}</div>
        <div v-if="(row as OpsApi.CronJob).humanExpr" class="text-xs text-gray-500">
          {{ (row as OpsApi.CronJob).humanExpr }}
        </div>
      </template>

      <template #command="{ row }">
        <div class="truncate font-mono text-xs" :title="(row as OpsApi.CronJob).command">
          {{ (row as OpsApi.CronJob).command }}
        </div>
      </template>

      <template #status="{ row }">
        <Switch
          :checked="(row as OpsApi.CronJob).status === 1"
          :loading="acting === `status-${(row as OpsApi.CronJob).id}`"
          :disabled="!canStatus || !writable"
          size="small"
          @change="(v: unknown) => onToggleStatus(row as OpsApi.CronJob, !!v)"
        />
      </template>

      <template #lastRun="{ row }">
        <div>{{ fmtTime((row as OpsApi.CronJob).lastRunAt) }}</div>
        <Tag
          v-if="(row as OpsApi.CronJob).lastExitCode !== undefined"
          :color="resultColor((row as OpsApi.CronJob).lastExitCode)"
          class="m-0 mt-1"
        >
          {{ resultLabel((row as OpsApi.CronJob).lastExitCode) }}
        </Tag>
      </template>

      <template #nextRun="{ row }">
        <div>{{ fmtTime((row as OpsApi.CronJob).nextRunAt) }}</div>
        <div class="text-xs text-gray-500">
          {{ fmtCountdown((row as OpsApi.CronJob).nextRunAt, tick) }}
        </div>
      </template>

      <template #running="{ row }">
        <Tag v-if="(row as OpsApi.CronJob).running" color="processing">执行中</Tag>
        <Tag v-else-if="(row as OpsApi.CronJob).failCount" color="warning">
          失败 {{ (row as OpsApi.CronJob).failCount }} 次
        </Tag>
        <span v-else class="text-xs text-gray-400">空闲</span>
      </template>

      <template #action="{ row }">
        <Space :size="4">
          <Button
            v-if="canRun"
            type="link"
            size="small"
            :disabled="!writable"
            :loading="acting === `run-${(row as OpsApi.CronJob).id}`"
            @click="onRun(row as OpsApi.CronJob)"
          >
            执行
          </Button>
          <Button type="link" size="small" @click="openLog(row as OpsApi.CronJob)">
            日志
          </Button>
          <Button
            v-if="canEdit"
            type="link"
            size="small"
            @click="openEdit(row as OpsApi.CronJob)"
          >
            编辑
          </Button>
          <Button
            v-if="canAdd"
            type="link"
            size="small"
            @click="openCopy(row as OpsApi.CronJob)"
          >
            复制
          </Button>
          <Popconfirm
            v-if="canDelete"
            title="删除该任务？执行日志会保留。"
            ok-text="删除"
            cancel-text="取消"
            @confirm="onDelete(row as OpsApi.CronJob)"
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      </template>
    </Grid>

    <CronFormDrawer
      v-model:open="formOpen"
      :record="editing"
      @submit="onSubmit"
    />
    <CronLogDrawer
      v-model:open="logOpen"
      :job="logJob"
      :can-run="canRun"
      :can-clean="canClean"
    />
    <CronDetailDrawer v-model:open="detailOpen" :job="detailJob" />
  </Page>
</template>
