<script lang="ts" setup>
/**
 * 服务管理（systemd）页面
 *
 * 重构要点：
 * 1. 数据源以 `list-unit-files` 为主表，叠加 `list-units --all` 与 `failed`，
 *    解决旧实现「只看到已加载单元」导致的数量偏少问题（本机约 225 个单元）。
 * 2. 真实系统能力全部经宿主代理执行（面板容器内无 systemctl/journalctl）；
 *    通道不可用时整页只读降级并给出安装指引。
 * 3. 列表统一用 VXE-Table（与用户管理页一致），支持分页/多选/列固定/批量操作。
 * 4. 高危动作（保护清单服务的停止/屏蔽、mask/unmask）需输入服务名二次确认；
 *    批量高危需输入 CONFIRM，与后端判定保持一致。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { OpsApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Dropdown,
  Input,
  Menu,
  MenuDivider,
  MenuItem,
  Modal,
  Space,
  Tag,
  message,
} from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  daemonReloadApi,
  getHostCapabilityApi,
  getServiceFailedApi,
  getServicePageApi,
  getServiceSummaryApi,
  refreshServiceSnapshotApi,
  serviceActionApi,
  serviceBatchApi,
} from '#/api';

import HostChannelBanner from '../components/HostChannelBanner.vue';
import ServiceDetailDrawer from './components/ServiceDetailDrawer.vue';

import {
  activeColor,
  activeLabel,
  fmtBytes,
  fmtDuration,
  unitFileStateColor,
  unitFileStateLabel,
} from './utils';

defineOptions({ name: 'OpsService' });

const { hasAccessByCodes } = useAccess();

const canLog = computed(() => hasAccessByCodes(['ops:service:log']));
const canManage = computed(() => hasAccessByCodes(['ops:service:manage']));
const canBatch = computed(() => hasAccessByCodes(['ops:service:batch']));
const canDanger = computed(() => hasAccessByCodes(['ops:service:danger']));

// ==================== 宿主通道 ====================

const capability = ref<OpsApi.HostCapability>();
const capLoading = ref(false);
const channelOk = computed(
  () => !!capability.value?.ok && (capability.value?.protocol ?? 0) >= 1,
);
/** 通道不可用时所有写操作禁用（只读降级） */
const writable = computed(() => channelOk.value);

async function loadCapability() {
  capLoading.value = true;
  try {
    capability.value = await getHostCapabilityApi();
  } finally {
    capLoading.value = false;
  }
}

function onCapabilityRefreshed(cap: OpsApi.HostCapability) {
  capability.value = cap;
  void reloadAll();
}

// ==================== 总览 ====================

const summary = ref<OpsApi.ServiceSummary>();
const failedList = ref<OpsApi.ServiceVO[]>([]);
const onlyFailed = ref(false);

async function loadOverview() {
  const [s, f] = await Promise.all([
    getServiceSummaryApi().catch(() => undefined),
    getServiceFailedApi().catch(() => [] as OpsApi.ServiceVO[]),
  ]);
  summary.value = s;
  failedList.value = f ?? [];
}

// ==================== 列表 ====================

const ACTIVE_OPTIONS = [
  { label: '运行中 (active)', value: 'active' },
  { label: '已停止 (inactive)', value: 'inactive' },
  { label: '失败 (failed)', value: 'failed' },
  { label: '启动中 (activating)', value: 'activating' },
];

const STATE_OPTIONS = [
  { label: '已启用 (enabled)', value: 'enabled' },
  { label: '已禁用 (disabled)', value: 'disabled' },
  { label: '静态 (static)', value: 'static' },
  { label: '已屏蔽 (masked)', value: 'masked' },
  { label: '自动生成 (generated)', value: 'generated' },
  { label: '间接 (indirect)', value: 'indirect' },
];

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '服务名或描述' },
      fieldName: 'keyword',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ACTIVE_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'active',
      label: '运行状态',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: STATE_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'unitFileState',
      label: '自启状态',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [{ label: '包含别名单元', value: true }],
        placeholder: '默认不含',
      },
      fieldName: 'includeAlias',
      label: '别名单元',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  checkboxConfig: { highlight: true, reserve: true },
  columns: [
    { type: 'checkbox', width: 42 },
    { title: '序号', type: 'seq', width: 56 },
    {
      field: 'name',
      minWidth: 210,
      slots: { default: 'name' },
      title: '服务单元',
    },
    {
      field: 'active',
      slots: { default: 'active' },
      title: '运行状态',
      width: 108,
    },
    {
      field: 'unitFileState',
      slots: { default: 'unitFileState' },
      title: '开机自启',
      width: 116,
    },
    {
      field: 'memoryBytes',
      formatter: ({ cellValue }: { cellValue?: number }) => fmtBytes(cellValue),
      title: '内存',
      width: 96,
    },
    {
      field: 'uptimeSeconds',
      formatter: ({ cellValue }: { cellValue?: number }) =>
        fmtDuration(cellValue),
      title: '运行时长',
      width: 112,
    },
    { field: 'restartCount', title: '重启', width: 68 },
    {
      field: 'protectedService',
      slots: { default: 'protected' },
      title: '保护',
      width: 72,
    },
    { field: 'description', minWidth: 180, title: '描述' },
    {
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: '操作',
      width: 258,
    },
  ],
  pagerConfig: { pageSize: 20, pageSizes: [20, 50, 100, 200] },
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const params: Parameters<typeof getServicePageApi>[0] = {
          ...(formValues as Record<string, unknown>),
          pageNum: page.currentPage,
          pageSize: page.pageSize,
        };
        // 「仅看失败」是页面级开关，优先级高于表单
        if (onlyFailed.value) {
          params.failedOnly = true;
        }
        const res = await getServicePageApi(params);
        return { items: res?.records ?? [], total: res?.total ?? 0 };
      },
    },
  },
  rowConfig: { keyField: 'name' },
};

const [Grid, gridApi] = useVbenVxeGrid({ formOptions, gridOptions });

async function reloadAll() {
  gridApi.query();
  await loadOverview();
}

function toggleOnlyFailed(value?: boolean) {
  onlyFailed.value = value === undefined ? !onlyFailed.value : value;
  gridApi.query();
}

// ==================== 选择 ====================

const selected = ref<string[]>([]);

function syncSelection() {
  const rows = (gridApi.grid?.getCheckboxRecords?.() ??
    []) as OpsApi.ServiceVO[];
  selected.value = rows.map((r) => r.name);
}

function clearSelection() {
  gridApi.grid?.clearCheckboxRow?.();
  selected.value = [];
}

// ==================== 动作 ====================

const acting = ref('');

/** 下拉里带 -now 的键映射到真实动作 */
const NOW_ACTIONS: Record<string, string> = {
  'disable-now': 'disable',
  'enable-now': 'enable',
};

const ACTION_LABELS: Record<string, string> = {
  disable: '取消开机自启',
  enable: '设为开机自启',
  kill: '结束主进程',
  mask: '屏蔽',
  reload: '重载配置',
  'reset-failed': '清除失败',
  restart: '重启',
  start: '启动',
  stop: '停止',
  'try-restart': '条件重启',
  unmask: '解除屏蔽',
};

function actionLabel(action: string) {
  return ACTION_LABELS[action] ?? action;
}

/** 高危二次确认状态机 */
const dangerOpen = ref(false);
const dangerTitle = ref('高危操作确认');
const dangerHint = ref('');
const dangerKeyword = ref('CONFIRM');
const dangerTyped = ref('');

interface PendingSingle {
  action: string;
  batch: false;
  name: string;
  now?: boolean;
}
interface PendingBatch {
  action: string;
  batch: true;
  names: string[];
  now?: boolean;
}
type PendingOp = PendingBatch | PendingSingle;
const pending = ref<PendingOp>();

function openDanger(
  payload: PendingOp,
  keyword: string,
  hint: string,
  title: string,
) {
  pending.value = payload;
  dangerKeyword.value = keyword;
  dangerHint.value = hint;
  dangerTyped.value = '';
  dangerTitle.value = title;
  dangerOpen.value = true;
}

function reportSingle(res: OpsApi.ServiceActionResult) {
  if (res.ok) {
    message.success(
      `「${res.name}」${actionLabel(res.action)}成功 · 当前 ${activeLabel(res.activeAfter)}`,
    );
  } else {
    message.error(
      `「${res.name}」${actionLabel(res.action)}失败：${res.message ?? '未知错误'}`,
    );
  }
}

async function execSingle(
  name: string,
  action: string,
  extra?: { confirm?: string; now?: boolean; signal?: string },
) {
  acting.value = `${name}:${action}`;
  try {
    const res = await serviceActionApi(name, {
      action,
      confirm: extra?.confirm,
      now: extra?.now,
      signal: extra?.signal,
    });
    if (res.confirmRequired) {
      // 后端拒绝执行并要求确认；此处不自动重试，交给确认弹窗补齐关键字
      openDanger(
        { action, batch: false, name, now: extra?.now },
        res.confirmKeyword || name,
        res.message || '该操作属于高危操作，请输入服务名以确认',
        '高危操作确认',
      );
      return res;
    }
    reportSingle(res);
    await reloadAll();
    return res;
  } finally {
    acting.value = '';
  }
}

const batchOpen = ref(false);
const batchResult = ref<OpsApi.ServiceBatchResult>();

async function execBatch(action: string, names: string[], confirm?: string) {
  acting.value = `batch:${action}`;
  try {
    const res = await serviceBatchApi({ action, confirm, names });
    if (res.confirmRequired) {
      openDanger(
        { action, batch: true, names },
        res.confirmKeyword || 'CONFIRM',
        `批量「${actionLabel(action)}」属于高危操作，请输入 ${res.confirmKeyword || 'CONFIRM'} 以确认（共 ${names.length} 个服务）`,
        '批量高危操作确认',
      );
      return res;
    }
    batchResult.value = res;
    batchOpen.value = true;
    if (res.failed === 0) {
      message.success(
        `批量${actionLabel(action)}完成：${res.success}/${res.total} 成功`,
      );
    } else {
      message.warning(
        `批量${actionLabel(action)}完成：成功 ${res.success}，失败 ${res.failed}`,
      );
    }
    await reloadAll();
    clearSelection();
    return res;
  } finally {
    acting.value = '';
  }
}

/** 列表行/抽屉触发的动作入口 */
async function fireAction(
  row: { name: string; protectedService?: boolean },
  key: string,
) {
  const action = NOW_ACTIONS[key] ?? key;
  const now = key.endsWith('-now');
  const signal = action === 'kill' ? 'SIGTERM' : undefined;
  await execSingle(row.name, action, { now, signal });
}

async function onDangerOk() {
  const typed = dangerTyped.value.trim();
  if (typed !== dangerKeyword.value) {
    message.error(`确认关键字不匹配，请输入 ${dangerKeyword.value}`);
    return;
  }
  const p = pending.value;
  dangerOpen.value = false;
  if (!p) {
    return;
  }
  if (p.batch) {
    await execBatch(p.action, p.names, typed);
  } else {
    await execSingle(p.name, p.action, { confirm: typed, now: p.now });
  }
}

// ==================== 抽屉 ====================

const drawerOpen = ref(false);
const drawerName = ref('');
const drawerTab = ref('overview');
const drawerKey = ref(0);

function openDrawer(row: OpsApi.ServiceVO, tab = 'overview') {
  drawerName.value = row.name;
  drawerTab.value = tab;
  drawerOpen.value = true;
}

async function onDrawerAction(payload: { name: string; action: string }) {
  await fireAction({ name: payload.name }, payload.action);
  drawerKey.value += 1;
}

// ==================== 工具栏动作 ====================

const toolBusy = ref('');

async function doRefreshSnapshot() {
  toolBusy.value = 'refresh';
  try {
    await refreshServiceSnapshotApi();
    await reloadAll();
    message.success('已强制刷新宿主快照');
  } finally {
    toolBusy.value = '';
  }
}

async function doDaemonReload() {
  toolBusy.value = 'daemon';
  try {
    await daemonReloadApi();
    await reloadAll();
    message.success('已执行 systemctl daemon-reload');
  } finally {
    toolBusy.value = '';
  }
}

function batchAction(action: string) {
  if (selected.value.length === 0) {
    message.warning('请先勾选要操作的服务');
    return;
  }
  void execBatch(action, [...selected.value]);
}

// ==================== 生命周期 ====================

onMounted(async () => {
  await Promise.all([loadCapability(), loadOverview()]);
});
</script>

<template>
  <Page>
    <HostChannelBanner
      :capability="capability"
      :loading="capLoading"
      @refreshed="onCapabilityRefreshed"
    />

    <!-- 总览统计（可点击筛选） -->
    <div class="mb-3 grid grid-cols-2 gap-2 md:grid-cols-4 xl:grid-cols-8">
      <button
        class="rounded border border-gray-200 p-2 text-left transition hover:border-blue-400 dark:border-gray-700"
        type="button"
        @click="toggleOnlyFailed(false)"
      >
        <div class="text-xs text-gray-500 dark:text-gray-400">单元总数</div>
        <div class="text-lg font-semibold">{{ summary?.total ?? '-' }}</div>
      </button>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">运行中</div>
        <div class="text-lg font-semibold text-green-600">{{ summary?.running ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">已停止</div>
        <div class="text-lg font-semibold">{{ summary?.stopped ?? '-' }}</div>
      </div>
      <button
        class="rounded border p-2 text-left transition hover:border-red-400 dark:border-gray-700"
        :class="[
          onlyFailed
            ? 'border-red-500 bg-red-50 dark:bg-red-950/30'
            : 'border-gray-200',
        ]"
        type="button"
        @click="toggleOnlyFailed()"
      >
        <div class="text-xs text-gray-500 dark:text-gray-400">
          失败 {{ onlyFailed ? '(筛选中)' : '' }}
        </div>
        <div
          class="text-lg font-semibold"
          :class="(summary?.failed ?? 0) > 0 ? 'text-red-600' : ''"
        >
          {{ summary?.failed ?? '-' }}
        </div>
      </button>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">已启用自启</div>
        <div class="text-lg font-semibold">{{ summary?.enabled ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">已禁用自启</div>
        <div class="text-lg font-semibold">{{ summary?.disabled ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">已屏蔽</div>
        <div class="text-lg font-semibold">{{ summary?.masked ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">systemd</div>
        <div class="text-sm font-semibold">
          {{ summary?.systemRunning ?? capability?.systemRunning ?? '-' }}
        </div>
      </div>
    </div>

    <!-- 失败单元聚合 -->
    <Alert v-if="failedList.length > 0" class="mb-3" show-icon type="error">
      <template #message>
        <span>检测到 {{ failedList.length }} 个失败单元，建议及时处理</span>
      </template>
      <template #description>
        <div class="flex flex-wrap items-center gap-1">
          <Tag
            v-for="item in failedList"
            :key="item.name"
            class="cursor-pointer"
            color="red"
            @click="openDrawer(item, 'logs')"
          >
            {{ item.name }}
          </Tag>
          <Button size="small" type="link" @click="onlyFailed = true">
            仅看失败单元
          </Button>
        </div>
      </template>
    </Alert>

    <!-- 批量操作条 -->
    <div
      v-if="selected.length > 0"
      class="mb-2 flex flex-wrap items-center gap-2 rounded border border-blue-200 bg-blue-50 p-2 text-sm dark:border-blue-800 dark:bg-blue-950/30"
    >
      <span>已选 {{ selected.length }} 个服务</span>
      <template v-if="canBatch && writable">
        <Button
          :loading="acting === 'batch:start'"
          size="small"
          @click="batchAction('start')"
        >
          批量启动
        </Button>
        <Button
          :loading="acting === 'batch:stop'"
          danger
          size="small"
          @click="batchAction('stop')"
        >
          批量停止
        </Button>
        <Button
          :loading="acting === 'batch:restart'"
          size="small"
          @click="batchAction('restart')"
        >
          批量重启
        </Button>
        <Button
          :loading="acting === 'batch:reload'"
          size="small"
          @click="batchAction('reload')"
        >
          批量重载
        </Button>
        <Button
          :loading="acting === 'batch:enable'"
          size="small"
          @click="batchAction('enable')"
        >
          批量启用自启
        </Button>
        <Button
          :loading="acting === 'batch:disable'"
          size="small"
          @click="batchAction('disable')"
        >
          批量禁用自启
        </Button>
      </template>
      <Button class="ml-auto" size="small" type="link" @click="clearSelection">
        清空选择
      </Button>
    </div>

    <Grid @checkbox-all="syncSelection" @checkbox-change="syncSelection">
      <template #toolbar-tools>
        <Space :size="4">
          <Button
            :loading="toolBusy === 'refresh'"
            size="small"
            @click="doRefreshSnapshot"
          >
            刷新快照
          </Button>
          <Button
            :loading="toolBusy === 'daemon'"
            size="small"
            @click="doDaemonReload"
          >
            daemon-reload
          </Button>
          <Button size="small" @click="reloadAll">刷新列表</Button>
        </Space>
      </template>

      <template #name="{ row }">
        <a class="font-medium" @click="openDrawer(row as OpsApi.ServiceVO)">
          {{ row.name }}
        </a>
      </template>

      <template #active="{ row }">
        <Tag :color="activeColor(row.active)">{{ activeLabel(row.active) }}</Tag>
        <div
          v-if="row.sub && row.sub !== 'running' && row.sub !== 'dead'"
          class="text-[11px] text-gray-400"
        >
          {{ row.sub }}
        </div>
      </template>

      <template #unitFileState="{ row }">
        <Tag :color="unitFileStateColor(row.unitFileState)">
          {{ unitFileStateLabel(row.unitFileState) }}
        </Tag>
      </template>

      <template #protected="{ row }">
        <Tag v-if="row.protectedService" color="orange">保护</Tag>
        <Tag v-else-if="row.alias" color="purple">别名</Tag>
        <span v-else class="text-gray-300">-</span>
      </template>

      <template #action="{ row }">
        <div class="flex items-center justify-center gap-1">
          <Button size="small" type="link" @click="openDrawer(row as OpsApi.ServiceVO)">
            详情
          </Button>
          <Button
            v-if="canLog"
            size="small"
            type="link"
            @click="openDrawer(row as OpsApi.ServiceVO, 'logs')"
          >
            日志
          </Button>
          <template v-if="canManage && writable">
            <Button
              v-if="row.active === 'active'"
              :loading="acting === `${row.name}:stop`"
              danger
              size="small"
              type="link"
              @click="fireAction(row, 'stop')"
            >
              停止
            </Button>
            <Button
              v-else
              :loading="acting === `${row.name}:start`"
              size="small"
              type="link"
              @click="fireAction(row, 'start')"
            >
              启动
            </Button>
            <Dropdown :trigger="['click']">
              <Button
                :loading="acting.startsWith(`${row.name}:`)"
                size="small"
                type="link"
              >
                更多 ▾
              </Button>
              <template #overlay>
                <Menu @click="(info: any) => fireAction(row, String(info.key))">
                  <MenuItem key="restart">重启</MenuItem>
                  <MenuItem key="try-restart">仅在运行中时重启</MenuItem>
                  <MenuItem key="reload">重载配置</MenuItem>
                  <MenuItem key="reset-failed">清除失败状态</MenuItem>
                  <MenuDivider />
                  <MenuItem key="enable">设为开机自启</MenuItem>
                  <MenuItem key="disable">取消开机自启</MenuItem>
                  <MenuItem key="enable-now">设为自启并立即启动</MenuItem>
                  <MenuItem key="disable-now">取消自启并立即停止</MenuItem>
                  <MenuDivider />
                  <MenuItem :disabled="!canDanger" danger key="mask">
                    屏蔽（禁止启动）
                  </MenuItem>
                  <MenuItem :disabled="!canDanger" danger key="unmask">
                    解除屏蔽
                  </MenuItem>
                  <MenuItem danger key="kill">结束主进程 (SIGTERM)</MenuItem>
                </Menu>
              </template>
            </Dropdown>
          </template>
        </div>
      </template>
    </Grid>

    <!-- 详情抽屉 -->
    <ServiceDetailDrawer
      v-model:open="drawerOpen"
      :acting="acting"
      :can-log="canLog"
      :can-manage="canManage && writable"
      :initial-tab="drawerTab"
      :name="drawerName"
      :reload-key="drawerKey"
      @action="onDrawerAction"
    />

    <!-- 高危二次确认 -->
    <Modal
      v-model:open="dangerOpen"
      :ok-button-props="{ danger: true }"
      :title="dangerTitle"
      ok-text="确认执行"
      @ok="onDangerOk"
    >
      <div class="space-y-3">
        <Alert :message="dangerHint" show-icon type="warning" />
        <div>
          <div class="mb-1 text-sm text-gray-600 dark:text-gray-300">
            请输入
            <span class="font-mono font-semibold text-red-600">{{ dangerKeyword }}</span>
            以确认：
          </div>
          <Input v-model:value="dangerTyped" :placeholder="dangerKeyword" allow-clear />
        </div>
      </div>
    </Modal>

    <!-- 批量结果 -->
    <Modal v-model:open="batchOpen" :footer="null" :width="600" title="批量操作结果">
      <div v-if="batchResult" class="max-h-[60vh] overflow-auto">
        <div class="mb-2 text-sm">
          共 {{ batchResult.total }} 项：成功
          <span class="font-semibold text-green-600">{{ batchResult.success }}</span>，失败
          <span class="font-semibold text-red-600">{{ batchResult.failed }}</span>
        </div>
        <div
          v-for="item in batchResult.items"
          :key="item.name"
          class="flex items-start gap-2 border-b border-gray-100 py-1 text-xs last:border-0 dark:border-gray-800"
        >
          <Tag :color="item.ok ? 'success' : 'error'">{{ item.ok ? '成功' : '失败' }}</Tag>
          <span class="w-56 shrink-0 truncate font-mono">{{ item.name }}</span>
          <span class="min-w-0 flex-1 break-all text-gray-500 dark:text-gray-400">
            {{ item.message }}
          </span>
        </div>
      </div>
    </Modal>
  </Page>
</template>
