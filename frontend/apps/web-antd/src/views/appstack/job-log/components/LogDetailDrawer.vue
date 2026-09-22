<script lang="ts" setup>
/**
 * 调度日志详情抽屉。
 *
 * <p>把「一次触发」拆成两段呈现，是这一页的主要信息架构：
 * <ol>
 *   <li><b>调度段（trigger）</b> —— 谁在什么时候把任务派发给了哪个执行器，成功与否；</li>
 *   <li><b>执行段（handle）</b> —— 执行体返回了什么，耗时多少，输出全文是什么。</li>
 * </ol>
 * 两段之间用一条分隔线断开，而不是把十几个字段平铺。因为排查时的第一个问题永远是
 * 「是没派发出去，还是派发出去了但跑挂了」——这一步必须在视觉上就能回答。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { JobLogApi } from '#/api';

import { computed, ref, watch } from 'vue';

import { Alert, Button, Drawer, Spin, Tag, message } from 'ant-design-vue';

import { getJobLogDetailApi, getJobLogOutputApi } from '#/api';

defineOptions({ name: 'AppstackJobLogDetailDrawer' });

const props = defineProps<{
  open: boolean;
  logId?: null | string;
}>();

const emit = defineEmits<{ 'update:open': [boolean] }>();

const STATUS_META: Record<string, { color: string; label: string }> = {
  DISCARDED: { color: 'default', label: '已丢弃' },
  FAILED: { color: 'error', label: '失败' },
  KILLED: { color: 'volcano', label: '已终止' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  TIMEOUT: { color: 'warning', label: '超时' },
};

const loading = ref(false);
const detail = ref<JobLogApi.JobLog>();
const output = ref('');
const outputLoading = ref(false);

const statusMeta = computed(
  () => STATUS_META[detail.value?.status ?? ''] ?? { color: 'default', label: '未知' },
);

/** 派发段是否失败 —— 这是排查的第一分叉点 */
const dispatchFailed = computed(
  () => detail.value?.triggerCode !== undefined && detail.value.triggerCode !== 0,
);

function formatTime(value?: string) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 19);
}

watch(
  () => props.open,
  (open) => {
    if (open) void load();
  },
);

async function load() {
  if (!props.logId) return;
  loading.value = true;
  output.value = '';
  try {
    detail.value = await getJobLogDetailApi(props.logId);
    void loadOutput();
  } catch {
    detail.value = undefined;
  } finally {
    loading.value = false;
  }
}

async function loadOutput() {
  if (!props.logId) return;
  outputLoading.value = true;
  try {
    output.value = await getJobLogOutputApi(props.logId);
  } catch {
    output.value = '';
  } finally {
    outputLoading.value = false;
  }
}

async function copyOutput() {
  try {
    await navigator.clipboard.writeText(output.value ?? '');
    message.success('已复制到剪贴板');
  } catch {
    message.warning('浏览器未授予剪贴板权限，请手动选中复制');
  }
}
</script>

<template>
  <Drawer
    :open="open"
    :width="860"
    title="调度日志详情"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <template v-if="detail">
        <Alert
          v-if="dispatchFailed"
          class="mb-3"
          show-icon
          type="error"
          message="派发阶段即失败，执行体未启动"
          :description="detail.triggerMsg || '调度器未能把任务交给执行器'"
        />

        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Tag :color="statusMeta.color">{{ statusMeta.label }}</Tag>
          <span class="font-medium">{{ detail.jobName || '-' }}</span>
          <Tag v-if="detail.handler">{{ detail.handler }}</Tag>
          <Tag>{{ detail.triggerType === 'MANUAL' ? '手动触发' : '定时触发' }}</Tag>
          <Tag v-if="(detail.retryIndex ?? 0) > 0" color="orange">
            重试 #{{ detail.retryIndex }}
          </Tag>
        </div>

        <!-- ==================== 调度段 ==================== -->
        <div class="mb-1 text-sm font-medium">调度段（谁把任务交出去的）</div>
        <div class="mb-3 rounded border p-3 text-sm">
          <div class="grid grid-cols-2 gap-y-1">
            <div>
              <span class="text-gray-500">触发时间：</span>
              {{ formatTime(detail.triggerTime) }}
            </div>
            <div>
              <span class="text-gray-500">触发方式：</span>
              {{ detail.triggerType || '-' }}
            </div>
            <div>
              <span class="text-gray-500">执行器：</span>
              <span class="font-mono text-xs">
                {{ detail.executorAppName || '-' }}（{{ detail.executorAddress || '-' }}）
              </span>
            </div>
            <div>
              <span class="text-gray-500">派发结果：</span>
              <Tag :color="detail.triggerCode === 0 ? 'success' : 'error'">
                {{ detail.triggerCode === 0 ? '成功' : `失败（${detail.triggerCode}）` }}
              </Tag>
            </div>
          </div>
          <div v-if="detail.triggerMsg" class="mt-2 rounded bg-red-50 p-2 text-xs text-red-700 dark:bg-red-950/40 dark:text-red-300">
            {{ detail.triggerMsg }}
          </div>
        </div>

        <!-- ==================== 执行段 ==================== -->
        <div class="mb-1 text-sm font-medium">执行段（执行体干了什么）</div>
        <div class="mb-3 rounded border p-3 text-sm">
          <div class="grid grid-cols-2 gap-y-1">
            <div>
              <span class="text-gray-500">开始时间：</span>
              {{ formatTime(detail.handleTime) }}
            </div>
            <div>
              <span class="text-gray-500">耗时：</span>
              {{ detail.handleDurationMs === null || detail.handleDurationMs === undefined
                ? '-'
                : `${detail.handleDurationMs} ms` }}
            </div>
            <div>
              <span class="text-gray-500">执行结果：</span>
              <Tag :color="detail.handleCode === 0 ? 'success' : 'error'">
                {{ detail.handleCode === 0 ? '成功' : `失败（${detail.handleCode}）` }}
              </Tag>
            </div>
            <div>
              <span class="text-gray-500">任务 ID：</span>
              <span class="font-mono text-xs">{{ detail.jobId || '-' }}</span>
            </div>
          </div>
          <div class="mt-2 text-sm">
            <span class="text-gray-500">结果摘要：</span>
            {{ detail.handleMsg || '-' }}
          </div>
        </div>

        <!-- ==================== 输出全文 ==================== -->
        <div class="mb-1 flex items-center gap-2">
          <span class="text-sm font-medium">执行输出全文</span>
          <Button class="ml-auto" size="small" @click="loadOutput">重新加载</Button>
          <Button size="small" :disabled="!output" @click="copyOutput">复制</Button>
        </div>
        <Spin :spinning="outputLoading">
          <pre
            class="max-h-96 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
          >{{ output || '（本次执行没有输出）' }}</pre>
        </Spin>
      </template>
      <div v-else class="text-sm text-gray-500">未找到该条日志。</div>
    </Spin>
  </Drawer>
</template>
