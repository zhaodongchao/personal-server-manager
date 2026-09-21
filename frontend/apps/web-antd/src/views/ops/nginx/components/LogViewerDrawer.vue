<script lang="ts" setup>
/**
 * Nginx 日志查看抽屉。
 *
 * <p>列出实例日志目录（log_dir）下的 .log 文件，选定后 tail 尾部若干行，
 * 支持自动刷新跟随最新日志。文件名仅允许实例 log_dir 下的文件（后端防路径穿越）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import { Button, Drawer, Empty, Select, Spin, Switch, Tooltip, message } from 'ant-design-vue';

import { getNginxLogListApi, getNginxLogTailApi } from '#/api';

defineOptions({ name: 'OpsNginxLogDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
}>();

interface LogFile {
  name: string;
  size: number;
}

const POLL_INTERVAL_MS = 2000;

const loading = ref(false);
const files = ref<LogFile[]>([]);
const currentFile = ref<string>();
const lines = ref<string[]>([]);
const lineCount = ref(200);
const follow = ref(true);
const autoRefresh = ref(true);
const scrollBox = ref<HTMLDivElement>();

let timer: any = null;
let inFlight = false;

async function loadFiles() {
  loading.value = true;
  try {
    const res = (await getNginxLogListApi(props.instanceId)) ?? [];
    files.value = res.map((m) => ({
      name: String(m.name ?? ''),
      size: Number(m.size ?? -1),
    }));
    const first = files.value[0];
    if (!currentFile.value && first) {
      currentFile.value = first.name;
    }
  } finally {
    loading.value = false;
  }
}

async function loadLines(silent = false) {
  if (!currentFile.value) {
    lines.value = [];
    return;
  }
  if (inFlight) return;
  inFlight = true;
  if (!silent) loading.value = true;
  try {
    lines.value =
      (await getNginxLogTailApi({
        instanceId: props.instanceId,
        file: currentFile.value,
        lines: lineCount.value,
      })) ?? [];
    if (follow.value) {
      await nextTick();
      const el = scrollBox.value;
      if (el) el.scrollTop = el.scrollHeight;
    }
  } catch {
    // 错误已由拦截器统一 toast
  } finally {
    inFlight = false;
    loading.value = false;
  }
}

function stopPoll() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function startPoll() {
  stopPoll();
  if (!props.open || !autoRefresh.value) return;
  timer = setInterval(() => loadLines(true), POLL_INTERVAL_MS);
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      stopPoll();
      return;
    }
    currentFile.value = undefined;
    lines.value = [];
    void loadFiles().then(() => {
      void loadLines();
      startPoll();
    });
  },
);

watch(currentFile, () => {
  void loadLines();
});

watch(lineCount, () => {
  void loadLines();
});

watch(autoRefresh, () => {
  if (autoRefresh.value) startPoll();
  else stopPoll();
});

onBeforeUnmount(stopPoll);

const empty = computed(() => !loading.value && lines.value.length === 0);

function fmtSize(size: number): string {
  if (size < 0) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(lines.value.join('\n'));
    message.success(`已复制 ${lines.value.length} 行`);
  } catch {
    message.error('复制失败（浏览器未授予剪贴板权限）');
  }
}
</script>

<template>
  <Drawer
    :open="open"
    :width="820"
    title="Nginx 日志"
    @close="emit('update:open', false)"
  >
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <Select
        v-model:value="currentFile"
        :options="files.map((f) => ({ label: `${f.name}（${fmtSize(f.size)}）`, value: f.name }))"
        class="w-80"
        placeholder="选择日志文件"
        show-search
      />
      <Select
        v-model:value="lineCount"
        :options="[
          { label: '100 行', value: 100 },
          { label: '200 行', value: 200 },
          { label: '500 行', value: 500 },
        ]"
        class="w-28"
      />
      <Tooltip title="实时刷新（短轮询 2s）">
        <span class="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
          <Switch v-model:checked="autoRefresh" size="small" />
          实时
        </span>
      </Tooltip>
      <Tooltip title="自动滚动到最新一行">
        <span class="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
          <Switch v-model:checked="follow" size="small" />
          跟随
        </span>
      </Tooltip>
      <Button :loading="loading" size="small" @click="loadLines()">刷新</Button>
      <Button class="ml-auto" size="small" type="link" @click="copyAll">复制</Button>
    </div>

    <Spin :spinning="loading">
      <div
        ref="scrollBox"
        class="h-[70vh] overflow-auto rounded border border-gray-200 bg-gray-50 p-2 font-mono text-[12px] leading-5 dark:border-gray-700 dark:bg-gray-900"
      >
        <Empty
          v-if="empty"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          description="请选择日志文件"
        />
        <div v-else class="space-y-0.5">
          <div
            v-for="(line, idx) in lines"
            :key="idx"
            class="break-all text-gray-700 hover:bg-white dark:text-gray-200 dark:hover:bg-gray-800/60"
          >
            {{ line }}
          </div>
        </div>
      </div>
    </Spin>

    <div class="mt-1 text-xs text-gray-400">
      共 {{ lines.length }} 行 · {{ currentFile ?? '未选择文件' }}
    </div>
  </Drawer>
</template>
