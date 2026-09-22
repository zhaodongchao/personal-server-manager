<script lang="ts" setup>
/**
 * 定时任务管理（应用栈）。
 *
 * <p>列表页承担四件事：看得见（顶部统计把「调度器是否在跑 / 宿主通道是否可用 / 有没有
 * 连续失败」摊在最显眼处）、改得动（启停 / 编辑 / 复制）、跑得了（立即执行与停止）、
 * 删得稳（删除与停止都要确认关键字，不做「一键静默清空」）。
 *
 * <p>为什么把「宿主通道不可用」做成页面级告警：SHELL 与 SERVICE 两类任务依赖面板到
 * 宿主的执行通道，通道断了任务会照常调度、照常记日志，但必然全部失败。这种「配置
 * 看起来没问题、跑起来全红」的局面最容易误判成任务本身写错了。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { JobApi } from '#/api';

import { h, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import {
  copyJobApi,
  deleteJobApi,
  disableJobApi,
  enableJobApi,
  getExecutorListApi,
  getJobHandlersApi,
  getJobOptionsApi,
  getJobPageApi,
  getJobStatsApi,
  runJobApi,
  stopJobApi,
} from '#/api';

import ExecutorDrawer from './components/ExecutorDrawer.vue';
import JobDrawer from './components/JobDrawer.vue';

defineOptions({ name: 'AppstackJob' });

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
const list = ref<JobApi.Job[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const keyword = ref('');
const handlerFilter = ref<string>();
const statusFilter = ref<number>();

const stats = ref<JobApi.Stats>();
const options = ref<JobApi.Options>();
const handlers = ref<JobApi.HandlerSchema[]>([]);
const executors = ref<JobApi.Executor[]>([]);

const handlerOptions = [
  { label: '宿主 Shell', value: 'SHELL' },
  { label: 'HTTP 接口', value: 'HTTP' },
  { label: 'systemd 服务', value: 'SERVICE' },
  { label: '面板内置', value: 'INTERNAL' },
];

const statusOptions = [
  { label: '已启用', value: 1 },
  { label: '已停用', value: 0 },
];

async function load() {
  loading.value = true;
  try {
    const res = await getJobPageApi({
      handler: handlerFilter.value || undefined,
      keyword: keyword.value || undefined,
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      status: statusFilter.value,
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

async function loadMeta() {
  const [s, o, h, e] = await Promise.all([
    getJobStatsApi().catch(() => undefined),
    getJobOptionsApi().catch(() => undefined),
    getJobHandlersApi().catch(() => []),
    getExecutorListApi().catch(() => []),
  ]);
  stats.value = s as JobApi.Stats;
  options.value = o as JobApi.Options;
  handlers.value = (h ?? []) as JobApi.HandlerSchema[];
  executors.value = (e ?? []) as JobApi.Executor[];
}

function search() {
  pagination.current = 1;
  void load();
}

function executorName(id?: string) {
  const hit = executors.value.find((item) => item.id === String(id));
  return hit?.executorName || hit?.appName || '-';
}

function formatTime(value?: string) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 19);
}

// ==================== 新建 / 编辑 ====================

const formOpen = ref(false);
const editing = ref<JobApi.Job | null>(null);

function openCreate() {
  editing.value = null;
  formOpen.value = true;
}

function openEdit(record: JobApi.Job) {
  editing.value = record;
  formOpen.value = true;
}

async function onSaved() {
  await Promise.all([load(), loadMeta()]);
}

// ==================== 立即执行 / 停止 ====================

const runBusyId = ref<null | string>(null);
const stopBusyId = ref<null | string>(null);
const togglingId = ref<null | string>(null);

async function doRun(record: JobApi.Job) {
  if (!record.id) return;
  runBusyId.value = record.id;
  try {
    const res = await runJobApi(record.id);
    message.success(res?.message || '已派发执行');
    await Promise.all([load(), loadMeta()]);
  } catch {
    // 错误提示由请求拦截器统一给出
  } finally {
    runBusyId.value = null;
  }
}

async function doStop(record: JobApi.Job) {
  if (!record.id) return;
  const confirm = `STOP JOB ${record.jobName}`;
  let input = '';
  Modal.confirm({
    content: h('div', { class: 'space-y-2' }, [
      h(
        'div',
        { class: 'text-sm' },
        '停止只会终止面板侧的等待与执行线程；真实执行体在宿主机上，无法跨容器反向 kill（会在日志里标 KILLED）。',
      ),
      h('div', { class: 'text-xs text-gray-500' }, [
        '请输入确认关键字：',
        h('span', { class: 'font-mono font-semibold text-red-600' }, confirm),
      ]),
      h(Input, {
        'onUpdate:value': (v: string) => {
          input = v;
        },
        placeholder: confirm,
      }),
    ]),
    onOk: async () => {
      if (input.trim() !== confirm) {
        message.error(`确认关键字不匹配，请输入 ${confirm}`);
        throw new Error('confirm mismatch');
      }
      stopBusyId.value = record.id ?? null;
      try {
        const res = await stopJobApi(record.id!, confirm);
        if (res?.stopped) {
          message.success(res.message);
        } else {
          message.info(res?.message || '该任务当前没有正在执行的实例');
        }
        await load();
      } finally {
        stopBusyId.value = null;
      }
    },
    title: '停止执行确认',
  });
}

async function doToggle(record: JobApi.Job, checked: boolean) {
  if (!record.id) return;
  togglingId.value = record.id;
  try {
    if (checked) {
      await enableJobApi(record.id);
      message.success('已启用，调度器已装载该任务');
    } else {
      await disableJobApi(record.id);
      message.success('已停用');
    }
    await Promise.all([load(), loadMeta()]);
  } catch {
    // 失败时回滚开关显示
    await load();
  } finally {
    togglingId.value = null;
  }
}

async function doCopy(record: JobApi.Job) {
  if (!record.id) return;
  try {
    await copyJobApi(record.id);
    message.success('已复制为新任务（默认停用，请核对参数后再启用）');
    await Promise.all([load(), loadMeta()]);
  } catch {
    // 错误提示由请求拦截器统一给出
  }
}

async function doDelete(record: JobApi.Job) {
  if (!record.id) return;
  const confirm = `DELETE JOB ${record.jobName}`;
  let input = '';
  Modal.confirm({
    content: h('div', { class: 'space-y-2' }, [
      h(
        'div',
        { class: 'text-sm' },
        `确定删除任务「${record.jobName}」？删除后不再调度；历史日志会保留（任务名已快照）。`,
      ),
      h('div', { class: 'text-xs text-gray-500' }, [
        '请输入确认关键字：',
        h('span', { class: 'font-mono font-semibold text-red-600' }, confirm),
      ]),
      h(Input, {
        'onUpdate:value': (v: string) => {
          input = v;
        },
        placeholder: confirm,
      }),
    ]),
    onOk: async () => {
      if (input.trim() !== confirm) {
        message.error(`确认关键字不匹配，请输入 ${confirm}`);
        throw new Error('confirm mismatch');
      }
      await deleteJobApi(record.id!, confirm);
      message.success('删除成功');
      await Promise.all([load(), loadMeta()]);
    },
    okButtonProps: { danger: true },
    title: '删除确认',
  });
}

// ==================== 执行器抽屉 ====================

const executorOpen = ref(false);

async function onExecutorClosed() {
  await Promise.all([load(), loadMeta()]);
}

onMounted(async () => {
  await loadMeta();
  await load();
});
</script>

<template>
  <Page class="flex flex-col">
    <!-- 顶部统计：把「跑没跑、稳不稳」摊在最显眼处 -->
    <div class="mb-3 grid grid-cols-2 gap-2 md:grid-cols-5">
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">任务总数</div>
        <div class="text-lg font-semibold">{{ stats?.total ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">已启用</div>
        <div class="text-lg font-semibold text-green-600">{{ stats?.enabled ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">连续失败</div>
        <div class="text-lg font-semibold" :class="(stats?.failing ?? 0) > 0 ? 'text-red-600' : ''">
          {{ stats?.failing ?? '-' }}
        </div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">今日触发</div>
        <div class="text-lg font-semibold">{{ stats?.todayRuns ?? '-' }}</div>
      </div>
      <div class="rounded border p-2">
        <div class="text-xs text-gray-500">执行中</div>
        <div class="text-lg font-semibold text-blue-600">{{ stats?.running ?? '-' }}</div>
      </div>
    </div>

    <Alert
      v-if="stats && !stats.schedulerEnabled"
      class="mb-3"
      show-icon
      type="warning"
      message="调度器总开关已关闭"
      description="配置项 serverpanel.job.scheduler.enabled 为 false，所有任务都不会被调度；手动「立即执行」仍可用。这是不必要回滚版本的故障处置开关。"
    />
    <Alert
      v-else-if="stats && !stats.hostChannelAvailable"
      class="mb-3"
      show-icon
      type="error"
      message="宿主执行通道不可用"
      description="面板到宿主的执行通道当前不通，SHELL 与 SERVICE 两类任务会全部失败（HTTP 与面板内置任务不受影响）。请先到运维工具排查宿主代理。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-56"
        placeholder="按任务名 / 描述过滤"
        @press-enter="search"
      />
      <Select
        v-model:value="handlerFilter"
        :options="handlerOptions"
        allow-clear
        class="w-40"
        placeholder="处理器"
        @change="search"
      />
      <Select
        v-model:value="statusFilter"
        :options="statusOptions"
        allow-clear
        class="w-32"
        placeholder="状态"
        @change="search"
      />
      <Button type="primary" @click="search">搜索</Button>
      <Button @click="load">刷新</Button>

      <div class="ml-auto flex items-center gap-2">
        <Button
          v-if="hasAccessByCodes(['appstack:job:executor'])"
          @click="executorOpen = true"
        >
          执行器管理
        </Button>
        <Button
          v-if="hasAccessByCodes(['appstack:job:save'])"
          type="primary"
          @click="openCreate"
        >
          新增任务
        </Button>
      </div>
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
        :row-key="(record: JobApi.Job) => record.id"
        :scroll="{ x: 1500 }"
        size="small"
      >
        <Table.Column key="jobName" title="任务" :width="220" fixed="left">
          <template #default="{ record }">
            <div class="font-medium">{{ record.jobName }}</div>
            <div v-if="record.jobDesc" class="text-xs text-gray-500">
              {{ record.jobDesc }}
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
        <Table.Column key="cronExpr" title="Cron" :width="150">
          <template #default="{ record }">
            <span class="font-mono text-xs">{{ record.cronExpr }}</span>
          </template>
        </Table.Column>
        <Table.Column key="executorId" title="执行器" :width="150">
          <template #default="{ record }">
            <span class="font-mono text-xs">{{ executorName(record.executorId) }}</span>
          </template>
        </Table.Column>
        <Table.Column key="nextFireTime" title="下次执行" :width="160">
          <template #default="{ record }">
            <span class="text-xs">{{ formatTime(record.nextFireTime) }}</span>
          </template>
        </Table.Column>
        <Table.Column key="lastStatus" title="上次执行" :width="190">
          <template #default="{ record }">
            <template v-if="record.lastStatus">
              <Tag :color="statusMeta(record.lastStatus).color">
                {{ statusMeta(record.lastStatus).label }}
              </Tag>
              <span class="text-xs text-gray-500">
                {{ formatTime(record.lastFireTime) }}
              </span>
            </template>
            <span v-else class="text-xs text-gray-400">尚未执行</span>
            <Tooltip v-if="(record.failStreak ?? 0) > 0" title="连续失败次数，成功一次即清零">
              <Tag class="ml-1" color="red">连败 {{ record.failStreak }}</Tag>
            </Tooltip>
          </template>
        </Table.Column>
        <Table.Column key="status" title="启用" :width="80">
          <template #default="{ record }">
            <Switch
              :checked="record.status === 1"
              :disabled="!hasAccessByCodes(['appstack:job:toggle'])"
              :loading="togglingId === record.id"
              size="small"
              @change="(checked: any) => doToggle(record as JobApi.Job, !!checked)"
            />
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" :width="320" fixed="right">
          <template #default="{ record }">
            <Space :size="2" wrap>
              <Button
                v-if="hasAccessByCodes(['appstack:job:run'])"
                :loading="runBusyId === record.id"
                size="small"
                type="link"
                @click="doRun(record as JobApi.Job)"
              >
                立即执行
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:job:stop'])"
                :loading="stopBusyId === record.id"
                size="small"
                type="link"
                @click="doStop(record as JobApi.Job)"
              >
                停止
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:job:save'])"
                size="small"
                type="link"
                @click="openEdit(record as JobApi.Job)"
              >
                编辑
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:job:save'])"
                size="small"
                type="link"
                @click="doCopy(record as JobApi.Job)"
              >
                复制
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:job:delete'])"
                danger
                size="small"
                type="link"
                @click="doDelete(record as JobApi.Job)"
              >
                删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <JobDrawer
      v-model:open="formOpen"
      :editing="editing"
      :executors="executors"
      :handlers="handlers"
      :options="options"
      @saved="onSaved"
    />

    <ExecutorDrawer
      v-model:open="executorOpen"
      @closed="onExecutorClosed"
    />
  </Page>
</template>
