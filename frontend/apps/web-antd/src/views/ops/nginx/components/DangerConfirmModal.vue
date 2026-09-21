<script lang="ts" setup>
/**
 * 危险操作二次确认弹窗（通用）。
 *
 * <p>后端对删站/删上游/删转发/删证书/回滚等 L3 操作要求键入「关键字」确认
 * （错误码 6011 NGINX_GUARD_TRIGGERED）。关键字即对象名（站点名/上游名/域名）
 * 或固定 {@code ROLLBACK}。本弹窗统一承载这一交互：键入关键字 → 回调确认。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import { ref, watch } from 'vue';

import { Alert, Input, Modal, message } from 'ant-design-vue';

defineOptions({ name: 'OpsNginxDangerConfirmModal' });

const props = defineProps<{
  open: boolean;
  title?: string;
  description?: string;
  /** 需要键入的确认关键字 */
  keyword: string;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  /** 键入正确关键字后触发 */
  confirm: [string];
}>();

const typed = ref('');

watch(
  () => props.open,
  (open) => {
    if (open) typed.value = '';
  },
);

function onOk() {
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
    :open="open"
    :title="title || '危险操作确认'"
    ok-text="确认执行"
    @cancel="emit('update:open', false)"
    @ok="onOk"
  >
    <Alert
      :message="description || '该操作属于高危操作，需二次确认。'"
      class="mb-3"
      show-icon
      type="warning"
    />
    <div class="mb-1 text-sm text-gray-600 dark:text-gray-300">
      请输入
      <span class="font-mono font-semibold text-red-600">{{ keyword }}</span>
      以确认：
    </div>
    <Input v-model:value="typed" :placeholder="keyword" allow-clear />
  </Modal>
</template>
