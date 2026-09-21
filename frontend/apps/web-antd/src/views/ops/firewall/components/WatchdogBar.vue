<script lang="ts" setup>
/**
 * 防火墙变更保护（看门狗）倒计时条
 *
 * <p>为什么要有这个条：防火墙是唯一一个「改错就失联」的模块。全局开关、默认策略这类操作生效后，
 * 若使用者其实是被挡在了门外，他根本没机会再点一次「撤销」。看门狗的做法是——变更生效的同时
 * 在宿主机上挂一个 `systemd-run --on-active=N` 的一次性定时器，N 秒内没人点「保留变更」就自动回滚。
 *
 * <p>前端职责：把剩余时间**显性地**倒计时出来，并给一个「保留变更」的入口。
 * 倒计时归零时通知父组件刷新状态（此时宿主机已自动回滚）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Alert, Button, Progress, Space } from 'ant-design-vue';

import { fmtSeconds } from '../utils';

defineOptions({ name: 'OpsFirewallWatchdogBar' });

const props = defineProps<{
  watchdog?: OpsApi.FirewallWatchdog | null;
  loading?: boolean;
}>();

const emit = defineEmits<{ confirm: []; expired: [] }>();

/** 剩余秒数；由本地每秒推进，避免每秒打一次接口 */
const left = ref(0);
/** 看门狗总时长（用于进度条百分比），归零前不会变 */
const total = ref(0);
const baseSeconds = ref(0);
const armedAt = ref(0);
const expiresAtMs = ref<number | undefined>(undefined);
let timer: ReturnType<typeof setInterval> | undefined;

function stop() {
  if (timer) {
    clearInterval(timer);
    timer = undefined;
  }
}

function tick() {
  const next =
    expiresAtMs.value !== undefined
      ? (expiresAtMs.value - Date.now()) / 1000
      : baseSeconds.value - (Date.now() - armedAt.value) / 1000;
  left.value = Math.max(0, next);
  if (left.value <= 0) {
    stop();
    emit('expired');
  }
}

watch(
  () => props.watchdog,
  (w) => {
    stop();
    if (!w || !w.id) {
      left.value = 0;
      total.value = 0;
      return;
    }
    baseSeconds.value = w.secondsLeft ?? 0;
    total.value = Math.max(1, w.secondsLeft ?? 0);
    armedAt.value = Date.now();
    const ms = w.expiresAt ? Date.parse(w.expiresAt) : Number.NaN;
    expiresAtMs.value = Number.isNaN(ms) ? undefined : ms;
    left.value = baseSeconds.value;
    if (left.value > 0) {
      timer = setInterval(tick, 1000);
    }
  },
  { immediate: true },
);

onBeforeUnmount(stop);

const percent = computed(() =>
  total.value <= 0 ? 0 : Math.round((left.value / total.value) * 100),
);

/** 低于 30 秒转红，提醒「再不确认就要回滚了」 */
const urgent = computed(() => left.value > 0 && left.value <= 30);
</script>

<template>
  <Alert v-if="watchdog && left > 0" class="mb-3" type="warning" show-icon>
    <template #message>
      <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
        <span class="font-medium">
          防火墙变更保护中：{{ fmtSeconds(left) }} 后自动回滚
        </span>
        <span class="text-xs text-gray-500">
          {{ watchdog.reason || '若在倒计时内未确认，宿主机将自动撤销本次变更' }}
        </span>
        <Progress
          :percent="percent"
          :show-info="false"
          :stroke-color="urgent ? '#cf1322' : '#faad14'"
          class="w-[160px]"
          size="small"
        />
        <Space class="ml-auto">
          <Button
            :loading="loading"
            size="small"
            type="primary"
            @click="emit('confirm')"
          >
            保留变更
          </Button>
        </Space>
      </div>
    </template>
  </Alert>
</template>

<style scoped>
:deep(.ant-alert-message) {
  width: 100%;
}
</style>
