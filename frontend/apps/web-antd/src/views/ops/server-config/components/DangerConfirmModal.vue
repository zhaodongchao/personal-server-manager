<script lang="ts" setup>
/**
 * 危险操作二次确认弹窗（服务器配置）。
 *
 * <p>后端对 L3 类别（sshd）的「一键生效 / 停止托管 / 按历史恢复」要求键入关键字
 * （错误码 6024 SERVER_CONFIG_SELF_LOCKOUT_RISK），关键字由后端下发
 * （见 `Category.applyKeyword`，形如 `APPLY sshd`）。本组件只承载交互，
 * 不自己拼关键字，避免前后端规则漂移。
 *
 * <p>「停止托管」会删除托管片段并重启服务（sshd），同样是破坏性动作，
 * 所以复用同一个弹窗，仅换文案。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import { ref, watch } from 'vue';

import { Alert, Checkbox, Input, Modal, message } from 'ant-design-vue';

defineOptions({ name: 'OpsServerConfigDangerConfirmModal' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title?: string;
    description?: string;
    /** 需要键入的确认关键字（后端下发） */
    keyword: string;
    /** 确认按钮文案 */
    okText?: string;
    /** 是否展示「已确认过风险」勾选框 */
    requireAck?: boolean;
    /** 确认时的额外提示（如将删除的文件路径） */
    extraLines?: string[];
  }>(),
  {
    title: '危险操作确认',
    description: '该操作属于高危操作，需二次确认。',
    okText: '确认执行',
    requireAck: true,
    extraLines: () => [],
  },
);

const emit = defineEmits<{
  'update:open': [boolean];
  /** 键入正确关键字后触发 */
  confirm: [string];
}>();

const typed = ref('');
const acked = ref(false);

watch(
  () => props.open,
  (open) => {
    if (open) {
      typed.value = '';
      acked.value = false;
    }
  },
);

function onOk() {
  if (props.requireAck && !acked.value) {
    message.warning('请先勾选「我已确认风险」');
    return;
  }
  if (typed.value.trim() !== props.keyword) {
    message.error(`确认关键字不匹配，请输入 ${props.keyword}`);
    return;
  }
  emit('confirm', typed.value.trim());
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :ok-button-props="{ danger: true }"
    :ok-text="okText"
    :open="open"
    :title="title"
    :width="560"
    @cancel="emit('update:open', false)"
    @ok="onOk"
  >
    <Alert :message="description" class="mb-3" show-icon type="warning" />

    <div
      v-if="extraLines.length > 0"
      class="mb-3 rounded bg-gray-50 p-2 font-mono text-xs leading-5 text-gray-700 dark:bg-white/5 dark:text-gray-200"
    >
      <div v-for="(line, index) in extraLines" :key="index">{{ line }}</div>
    </div>

    <div class="mb-1 text-sm text-gray-600 dark:text-gray-300">
      请输入
      <span class="font-mono font-semibold text-red-600">{{ keyword }}</span>
      以确认：
    </div>
    <Input v-model:value="typed" :placeholder="keyword" allow-clear />

    <Checkbox v-if="requireAck" v-model:checked="acked" class="mt-3">
      <span class="text-xs">
        我已确认风险：清楚该操作会改写系统配置并触发服务重载/重启，且已保留其他可登录途径。
      </span>
    </Checkbox>
  </Modal>
</template>
