<script lang="ts" setup>
/**
 * 服务日志面板
 *
 * 设计意图：把 journald 的结构化日志（时间/级别/PID/消息）以可筛选、可实时
 * 跟随的形式呈现。宿主侧命令是 `journalctl -o json`，后端逐行解析为
 * SysLogLine，前端不再解析任何本地化时间串。
 *
 * 实时策略：短轮询 1.5s（已确认方案）。仅在面板可见且未暂停时轮询；轮询
 * 采用「上一轮未回来就不发下一轮」的方式天然限流，避免慢查询叠加。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import {
  Button,
  Input,
  Select,
  Switch,
  Tag,
  Tooltip,
  message,
} from 'ant-design-vue';

import { getServiceLogsApi } from '#/api';

defineOptions({ name: 'OpsServiceLogPanel' });

const props = withDefaults(
  defineProps<{
    name: string;
    /** 面板是否处于可见激活状态（非激活则停止轮询） */
    active?: boolean;
  }>(),
  { active: true },
);

/** 轮询间隔：1.5s（方案已确认） */
const POLL_INTERVAL_MS = 1500;

const loading = ref(false);
const lines = ref<OpsApi.SysLogLine[]>([]);
const scrollBox = ref<HTMLDivElement>();

const lineCount = ref(200);
const since = ref('-30min');
const keyword = ref('');
const minLevel = ref<number | undefined>(undefined);
const follow = ref(true);
const autoRefresh = ref(true);

let timer: any = null;
/** 上一轮是否仍在途中：true 则跳过本轮，避免请求堆积 */
let inFlight = false;

const LEVEL_OPTIONS = [
  { label: '全部级别', value: undefined },
  { label: '错误及以上 (err)', value: 3 },
  { label: '警告及以上 (warning)', value: 4 },
  { label: '通知及以上 (notice)', value: 5 },
  { label: '信息及以上 (info)', value: 6 },
  { label: '调试 (debug)', value: 7 },
];

/** 级别 -> Tag 颜色（journal 优先级 0 最高） */
const LEVEL_COLOR: Record<number, string> = {
  0: 'red',
  1: 'red',
  2: 'red',
  3: 'volcano',
  4: 'orange',
  5: 'blue',
  6: 'default',
  7: 'default',
};

const empty = computed(() => !loading.value && lines.value.length === 0);

function levelColor(level?: number) {
  if (level === undefined || level === null) {
    return 'default';
  }
  return LEVEL_COLOR[level] ?? 'default';
}

async function load(silent = false) {
  if (!props.name) {
    return;
  }
  if (inFlight) {
    return;
  }
  inFlight = true;
  if (!silent) {
    loading.value = true;
  }
  try {
    const res = await getServiceLogsApi(props.name, {
      lines: lineCount.value,
      since: since.value || undefined,
      keyword: keyword.value || undefined,
      minLevel: minLevel.value,
    });
    lines.value = res ?? [];
    if (follow.value) {
      await nextTick();
      const el = scrollBox.value;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    }
  } catch {
    // 错误已由请求拦截器统一 toast；这里吞掉以免打断轮询节奏
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
  if (!props.active || !autoRefresh.value) {
    return;
  }
  timer = setInterval(() => load(true), POLL_INTERVAL_MS);
}

watch(
  () => [props.active, props.name],
  () => {
    if (props.active) {
      void load();
      startPoll();
    } else {
      stopPoll();
    }
  },
  { immediate: true },
);

watch(autoRefresh, () => {
  if (autoRefresh.value) {
    startPoll();
  } else {
    stopPoll();
  }
});

onBeforeUnmount(stopPoll);

async function copyAll() {
  const text = lines.value
    .map((l) => `${l.time ?? ''} [${l.levelName ?? ''}] ${l.message ?? ''}`)
    .join('\n');
  try {
    await navigator.clipboard.writeText(text);
    message.success(`已复制 ${lines.value.length} 行日志`);
  } catch {
    message.error('复制失败（浏览器未授予剪贴板权限）');
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 过滤条 -->
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <Select
        v-model:value="lineCount"
        :options="[
          { label: '100 行', value: 100 },
          { label: '200 行', value: 200 },
          { label: '500 行', value: 500 },
          { label: '1000 行', value: 1000 },
        ]"
        class="w-28"
        size="small"
      />
      <Select
        v-model:value="since"
        :options="[
          { label: '最近 5 分钟', value: '-5min' },
          { label: '最近 30 分钟', value: '-30min' },
          { label: '最近 1 小时', value: '-1h' },
          { label: '最近 6 小时', value: '-6h' },
          { label: '最近 24 小时', value: '-24h' },
          { label: '最近 7 天', value: '-7d' },
          { label: '全部', value: '' },
        ]"
        class="w-32"
        size="small"
      />
      <Select
        v-model:value="minLevel"
        :options="LEVEL_OPTIONS"
        class="w-44"
        placeholder="级别"
        size="small"
      />
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-48"
        placeholder="消息关键字"
        size="small"
        @press-enter="load()"
      />
      <Button :loading="loading" size="small" @click="load()">查询</Button>
      <Tooltip title="实时跟随最新日志（短轮询 1.5s）">
        <span class="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
          <Switch v-model:checked="autoRefresh" size="small" />
          实时
        </span>
      </Tooltip>
      <Tooltip title="保持在最底部，自动滚动到最新一行">
        <span class="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
          <Switch v-model:checked="follow" size="small" />
          跟随
        </span>
      </Tooltip>
      <Button class="ml-auto" size="small" type="link" @click="copyAll">复制</Button>
    </div>

    <!-- 日志正文 -->
    <div
      ref="scrollBox"
      class="min-h-0 flex-1 overflow-auto rounded border border-gray-200 bg-gray-50 p-2 font-mono text-[12px] leading-5 dark:border-gray-700 dark:bg-gray-900"
    >
      <div v-if="empty" class="p-6 text-center text-xs text-gray-400">
        所选条件下没有日志
      </div>
      <div v-else class="space-y-0.5">
        <div
          v-for="(line, idx) in lines"
          :key="`${line.timestamp ?? idx}-${idx}`"
          class="flex items-start gap-2 rounded px-1 py-0.5 hover:bg-white dark:hover:bg-gray-800/60"
        >
          <span class="shrink-0 text-gray-400 dark:text-gray-500">{{ line.time || '-' }}</span>
          <Tag :color="levelColor(line.level)" class="!mr-0 shrink-0">
            {{ line.levelName || '-' }}
          </Tag>
          <span
            v-if="line.pid"
            class="shrink-0 text-gray-400 dark:text-gray-500"
          >[{{ line.pid }}]</span>
          <span class="min-w-0 flex-1 break-all text-gray-700 dark:text-gray-200">{{ line.message }}</span>
        </div>
      </div>
    </div>

    <div class="mt-1 shrink-0 text-xs text-gray-400">
      共 {{ lines.length }} 行 · 来源 journalctl -o json · 级别 0=emerg 最高、7=debug 最低
    </div>
  </div>
</template>
