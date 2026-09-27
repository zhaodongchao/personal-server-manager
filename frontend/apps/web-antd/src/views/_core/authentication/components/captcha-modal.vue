<script lang="ts" setup>
import type { AuthApi } from '#/api';

import { $t } from '@vben/locales';

import { Modal as AModal, Spin } from 'ant-design-vue';

import ClickCaptcha from './click-captcha.vue';

/**
 * 人机校验弹窗（注册页 / 邮箱登录页 / 密码登录页共用）。
 *
 * 展示后端下发的点选验证码，用户点满后由调用方（useMailCaptcha）上报坐标换取一次性令牌；
 * 本组件不参与判定。图片加载完成前显示加载态，purpose 只影响说明文案
 * （send 为「发验证码」场景，login 为「登录」场景）。
 */
const props = defineProps<{
  open: boolean;
  /** 验证码图片（data URI）；为空表示挑战尚未加载完成 */
  image?: string;
  /** 需按顺序点击的目标字符 */
  prompt?: string[];
  /** 加载挑战 / 校验中 */
  verifying?: boolean;
  error?: string;
  /** send=发邮件验证码（默认）；login=密码登录 —— 仅用于切换说明文案 */
  purpose?: 'send' | 'login';
}>();

const emit = defineEmits<{
  close: [];
  complete: [clicks: AuthApi.CaptchaClickPoint[]];
  refresh: [];
}>();
</script>

<template>
  <AModal
    :footer="null"
    :mask-closable="false"
    :open="props.open"
    :title="$t('authentication.captchaTitle')"
    width="380px"
    @cancel="emit('close')"
  >
    <p class="text-muted-foreground mb-3 text-sm">
      {{
        props.purpose === 'login'
          ? $t('authentication.captchaDescLogin')
          : $t('authentication.captchaDesc')
      }}
    </p>

    <div v-if="props.image && props.prompt?.length" class="relative">
      <ClickCaptcha
        :disabled="props.verifying"
        :image="props.image"
        :prompt="props.prompt"
        @complete="(clicks) => emit('complete', clicks)"
        @refresh="emit('refresh')"
      />
      <div
        v-if="props.verifying"
        class="absolute inset-0 flex items-center justify-center bg-white/60"
      >
        <Spin size="small" />
      </div>
    </div>
    <div v-else class="flex h-36 items-center justify-center">
      <Spin size="small" />
    </div>

    <p v-if="props.verifying" class="text-muted-foreground mt-2 text-sm">
      {{ $t('authentication.captchaVerifying') }}
    </p>
    <p v-if="props.error" class="text-destructive mt-1 text-sm">
      {{ props.error }}
    </p>
  </AModal>
</template>
