<script lang="ts" setup>
/**
 * 宿主通道状态条（运维三页共用）
 *
 * 设计意图：面板运行在容器内，容器里没有 systemctl / journalctl / ufw，
 * 这些能力必须经宿主代理（psm-hostagent）执行。本组件把「通道不可用」
 * 这一事实显式呈现给使用者，并给出可执行的安装指引。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, ref } from 'vue';

import { Button, Tag, Tooltip, message } from 'ant-design-vue';

import { probeHostCapabilityApi } from '#/api';

defineOptions({ name: 'OpsHostChannelBanner' });

const props = defineProps<{
  capability?: OpsApi.HostCapability | null;
  loading?: boolean;
}>();

const emit = defineEmits<{ refreshed: [OpsApi.HostCapability] }>();

const probing = ref(false);

/** 通道可用（协议也必须兼容，避免新旧版本半通） */
const ok = computed(
  () => !!props.capability?.ok && (props.capability?.protocol ?? 0) >= 1,
);

const modeLabel = computed(() => {
  switch (props.capability?.mode) {
    case 'hostagent': {
      return '宿主代理';
    }
    case 'nsenter': {
      return '旁车注入';
    }
    default: {
      return '未接入';
    }
  }
});

/** 工具可用概览：已解析路径的工具名列表 */
const toolList = computed(() =>
  Object.keys(props.capability?.tools ?? {}).sort(),
);

async function doProbe() {
  probing.value = true;
  try {
    const cap = await probeHostCapabilityApi();
    emit('refreshed', cap);
    if (cap.ok) {
      message.success(`宿主通道已连通（${cap.os ?? '未知系统'}）`);
    } else {
      message.warning(cap.message ?? '宿主通道仍不可用');
    }
  } finally {
    probing.value = false;
  }
}
</script>

<template>
  <div
    v-if="capability && !ok"
    class="mb-3 rounded border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/40"
  >
    <div class="flex items-start gap-2">
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2 font-medium text-red-700 dark:text-red-300">
          <span>宿主执行通道不可用，本页已降级为只读</span>
          <Tag color="red">{{ modeLabel }}</Tag>
        </div>
        <div class="mt-1 text-xs text-red-600 dark:text-red-300/90">
          {{ capability.message || '面板容器内没有 systemctl / journalctl / ufw，需接入宿主代理后才能执行实际系统操作。' }}
        </div>
        <div
          v-if="capability.missing && capability.missing.length > 0"
          class="mt-1 text-xs text-red-600 dark:text-red-300/90"
        >
          缺失工具：{{ capability.missing.join('、') }}
        </div>
        <pre
          v-if="capability.installHint"
          class="mt-2 max-h-40 overflow-auto rounded bg-white/70 p-2 font-mono text-[11px] leading-5 text-gray-700 dark:bg-black/30 dark:text-gray-200"
        >{{ capability.installHint }}</pre>
      </div>
      <Button :loading="probing || loading" size="small" @click="doProbe">
        重新探测
      </Button>
    </div>
  </div>

  <div
    v-else-if="capability"
    class="mb-3 flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400"
  >
    <Tag color="green">宿主通道正常</Tag>
    <span>{{ capability.os || '-' }}</span>
    <span class="text-gray-300 dark:text-gray-600">|</span>
    <span>systemd：{{ capability.systemRunning || '-' }}</span>
    <span class="text-gray-300 dark:text-gray-600">|</span>
    <span>防火墙：{{ capability.firewallBackend || 'none' }}</span>
    <span class="text-gray-300 dark:text-gray-600">|</span>
    <Tooltip>
      <template #title>
        <div v-if="toolList.length === 0">无工具信息</div>
        <div v-else class="max-w-xs break-all">{{ toolList.join('、') }}</div>
      </template>
      <span>工具 {{ toolList.length }} 个</span>
    </Tooltip>
    <Button
      class="ml-auto"
      :loading="probing || loading"
      size="small"
      type="link"
      @click="doProbe"
    >
      重新探测
    </Button>
  </div>
</template>
