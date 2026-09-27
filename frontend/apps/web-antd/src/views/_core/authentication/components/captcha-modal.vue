<script lang="ts" setup>
import { SliderCaptcha } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { Modal as AModal, Spin } from 'ant-design-vue';

/**
 * 人机校验滑块弹窗（注册页 / 邮箱登录页 / 密码登录页共用）。
 *
 * 仅负责展示滑块并把「拖动完成」这一刻的时长回传；令牌的领取与校验兑换由
 * 调用方（useMailCaptcha）负责。每次打开用 nonce 强制重挂载，清掉上一次通过态。
 *
 * purpose 只影响说明文案：send 为「发验证码」场景，login 为「登录」场景。
 */
const props = defineProps<{
  open: boolean;
  verifying?: boolean;
  error?: string;
  nonce?: number;
  /** send=发邮件验证码（默认）；login=密码登录 —— 仅用于切换说明文案 */
  purpose?: 'send' | 'login';
}>();

const emit = defineEmits<{
  success: [time: number];
  close: [];
}>();

interface SliderSuccessPayload {
  isPassing: boolean;
  time: number | string;
}

function handleSuccess(payload: SliderSuccessPayload) {
  emit('success', Number(payload.time));
}
</script>

<template>
  <AModal
    :open="props.open"
    :footer="null"
    :mask-closable="false"
    :title="$t('authentication.captchaTitle')"
    width="420px"
    @cancel="emit('close')"
  >
    <p class="text-muted-foreground mb-4 text-sm">
      {{
        props.purpose === 'login'
          ? $t('authentication.captchaDescLogin')
          : $t('authentication.captchaDesc')
      }}
    </p>
    <SliderCaptcha
      v-if="props.open"
      :key="props.nonce"
      @success="handleSuccess"
    />
    <div class="mt-3 flex items-center gap-2">
      <Spin v-if="props.verifying" :size="'small'" />
      <span v-if="props.verifying" class="text-sm text-muted-foreground">
        {{ $t('authentication.captchaVerifying') }}
      </span>
    </div>
    <p v-if="props.error" class="mt-2 text-sm text-destructive">
      {{ props.error }}
    </p>
  </AModal>
</template>
