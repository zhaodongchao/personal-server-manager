<script lang="ts" setup>
/**
 * 计划任务执行日志抽屉
 *
 * 两个关键交互：
 * 1. **手动执行后自动跟随**：点「立即执行」拿到 logId，随后轮询单条日志直到
 *    finishedAt 非空（= 执行结束），替代过去「点完不知道跑没跑完、只能手刷」的体验。
 *    轮询采用「上一轮未回来就不发下一轮」的方式天然限流。
 * 2. **输出就地查看与下载**：输出可能很长（上限 64KB），列表里只展示首行预览，
 *    点开行才展开全文；下载直接用已取到的输出构造 Blob，不再额外请求一次接口。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Empty,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  TypographyText,
  message,
} from 'ant-design-vue';

import {
  clearCronLogsApi,
  getCronLogDetailApi,
  getCronLogPageApi,
  runCronJobApi,
} from '#/api';

import {
  RESULT_OPTIONS,
  fmtDuration,
  fmtTime,
  resultColor,
  resultLabel,
  triggerLabel,
} from '../utils';

defineOptions({ name: 'OpsCronLogDrawer' });

const props = defineProps<{
  open: boolean;
  job: OpsApi.CronJob | null;
  canRun?: boolean;
  canClean?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
}>();

/** 轮询间隔 */
const POLL_INTERVAL_MS = 1500;
/** 单次执行的跟随上限：任务超时 + 30s 冗余 */
const POLL_MAX_MS = 600_000;

const loading = ref(false);
const rows = ref<OpsApi.CronLog[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const resultFilter = ref<string>();

/** 正在跟随执行的日志 ID */
const followingId = ref<string>();
const running = ref(false);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollStartedAt = 0;

const expandedKeys = ref<string[]>([]);

const columns = [
  { title: '开始时间', dataIndex: 'startedAt', width: 180 },
  { title: '触发方式', dataIndex: 'triggerType', width: 140 },
  { title: '结果', dataIndex: 'exitCode', width: 110 },
  { title: '耗时', dataIndex: 'durationMs', width: 110 },
  { title: '输出', dataIndex: 'output' },
];

const jobId = computed(() => props.job?.id ?? '');

watch(
  () => [props.open, props.job?.id] as const,
  ([open]) => {
    if (!open) {
      stopFollow();
      return;
    }
    pageNum.value = 1;
    expandedKeys.value = [];
    void load();
  },
);

async function load() {
  if (!jobId.value) return;
  loading.value = true;
  try {
    const res = await getCronLogPageApi(jobId.value, {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      result: resultFilter.value,
    });
    const data = (res ?? {}) as { records?: OpsApi.CronLog[]; total?: number };
    rows.value = data.records ?? [];
    total.value = data.total ?? 0;
  } catch {
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function onTableChange(pager: { current?: number; pageSize?: number }) {
  pageNum.value = pager.current ?? 1;
  pageSize.value = pager.pageSize ?? 10;
  void load();
}

async function onRun() {
  if (!jobId.value) return;
  running.value = true;
  try {
    const logId = await runCronJobApi(jobId.value);
    followingId.value = String(logId);
    pollStartedAt = Date.now();
    message.info('已触发执行，正在跟随本次日志…');
    void poll();
    await load();
  } catch (e: any) {
    message.error(e?.message ?? '触发执行失败');
  } finally {
    running.value = false;
  }
}

/** 轮询单条日志直到 finishedAt 非空；上一轮未回来不发下一轮 */
async function poll() {
  if (!followingId.value || !jobId.value) return;
  const id = followingId.value;
  const jid = jobId.value;
  try {
    const row = await getCronLogDetailApi(jid, id);
    if (row?.finishedAt) {
      followingId.value = undefined;
      await load();
      message.success(`执行结束：${resultLabel(row.exitCode)}`);
      return;
    }
  } catch {
    followingId.value = undefined;
    return;
  }
  if (Date.now() - pollStartedAt > POLL_MAX_MS) {
    followingId.value = undefined;
    message.warning('等待执行结果超时，可稍后手动刷新');
    return;
  }
  pollTimer = setTimeout(poll, POLL_INTERVAL_MS);
}

function stopFollow() {
  followingId.value = undefined;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = undefined;
  }
}

onBeforeUnmount(stopFollow);

/** 下载输出：直接用已取到的文本构造 Blob，避免再打一次需要鉴权的下载接口 */
function download(row: OpsApi.CronLog) {
  const text = row.output ?? '';
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cron-${row.jobId}-${row.id}.log`;
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

async function onClear() {
  if (!jobId.value) return;
  await clearCronLogsApi(jobId.value);
  message.success('已清空该任务的执行日志');
  await load();
}

function firstLine(text?: string): string {
  return (text ?? '').split('\n')[0]?.slice(0, 120) ?? '';
}
</script>

<template>
  <Drawer
    :open="props.open"
    :width="880"
    :title="`执行日志 · ${props.job?.name ?? ''}`"
    @close="emit('update:open', false)"
  >
    <template #extra>
      <Space>
        <Button
          v-if="props.canRun"
          type="primary"
          :loading="running || !!followingId"
          @click="onRun"
        >
          立即执行
        </Button>
        <Button :loading="loading" @click="load">刷新</Button>
        <Popconfirm
          v-if="props.canClean"
          title="清空该任务的全部执行日志？"
          ok-text="清空"
          cancel-text="取消"
          @confirm="onClear"
        >
          <Button danger>清空日志</Button>
        </Popconfirm>
      </Space>
    </template>

    <Space direction="vertical" class="w-full" :size="12">
      <Space :size="8" wrap>
        <Select
          v-model:value="resultFilter"
          :options="RESULT_OPTIONS"
          allow-clear
          placeholder="按结果筛选"
          style="width: 160px"
          @change="
            () => {
              pageNum = 1;
              load();
            }
          "
        />
        <TypographyText type="secondary">
          表达式：{{ props.job?.cronExpr }}
          <span v-if="props.job?.humanExpr">
            （{{ props.job.humanExpr }}）
          </span>
        </TypographyText>
      </Space>

      <Alert
        v-if="followingId"
        type="info"
        show-icon
        message="本次执行尚未结束，正在自动跟随日志…"
      />

      <Spin :spinning="loading">
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="{
            current: pageNum,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t: number) => `共 ${t} 条`,
          }"
          :row-key="(r: OpsApi.CronLog) => r.id"
          size="small"
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'startedAt'">
              {{ fmtTime((record as OpsApi.CronLog).startedAt) }}
            </template>
            <template v-else-if="column.dataIndex === 'triggerType'">
              {{
                triggerLabel(
                  (record as OpsApi.CronLog).triggerType,
                  (record as OpsApi.CronLog).operator,
                )
              }}
            </template>
            <template v-else-if="column.dataIndex === 'exitCode'">
              <Space :size="4">
                <Tag :color="resultColor((record as OpsApi.CronLog).exitCode)">
                  {{ resultLabel((record as OpsApi.CronLog).exitCode) }}
                </Tag>
                <TypographyText
                  v-if="(record as OpsApi.CronLog).timedOut"
                  type="warning"
                  class="text-xs"
                >
                  超时
                </TypographyText>
              </Space>
            </template>
            <template v-else-if="column.dataIndex === 'durationMs'">
              {{ fmtDuration((record as OpsApi.CronLog).durationMs) }}
            </template>
            <template v-else-if="column.dataIndex === 'output'">
              <div class="flex items-start justify-between gap-2">
                <div class="min-w-0 flex-1">
                  <div class="truncate font-mono text-xs">
                    {{ firstLine((record as OpsApi.CronLog).output) }}
                  </div>
                  <div
                    v-if="expandedKeys.includes((record as OpsApi.CronLog).id)"
                    class="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                  >
                    {{ (record as OpsApi.CronLog).output || '（无输出）' }}
                  </div>
                </div>
                <Space :size="4">
                  <Button
                    size="small"
                    type="link"
                    @click="
                      () => {
                        const id = (record as OpsApi.CronLog).id;
                        expandedKeys = expandedKeys.includes(id)
                          ? expandedKeys.filter((k) => k !== id)
                          : [...expandedKeys, id];
                      }
                    "
                  >
                    {{
                      expandedKeys.includes((record as OpsApi.CronLog).id)
                        ? '收起'
                        : '展开'
                    }}
                  </Button>
                  <Tooltip title="下载完整输出">
                    <Button
                      size="small"
                      type="link"
                      @click="download(record as OpsApi.CronLog)"
                    >
                      下载
                    </Button>
                  </Tooltip>
                </Space>
              </div>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无执行日志" />
          </template>
        </Table>
      </Spin>
    </Space>
  </Drawer>
</template>
