<script lang="ts" setup>
import type { Recordable } from '@vben/types';
import type { VbenFormSchema } from '@vben/common-ui';

import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { useMailAuth } from '#/composables/use-mail-auth';
import { useMailCaptcha } from '#/composables/use-mail-captcha';
import { useAuthStore } from '#/store';

import ThirdPartyLoginPanel from './components/third-party-login-panel.vue';
import CaptchaModal from './components/captcha-modal.vue';

/**
 * 密码登录。
 *
 * 与邮箱发码同体系：登录敏感操作前必须先通过点选人机校验，拿到一次性
 * 登录令牌（captcha:login:）随登录请求上报，后端原子消费；缺失/失效返回
 * 1037/1038。令牌单次使用，登录失败（含密码错误）后清空，下次需重新校验。
 */
defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const route = useRoute();
const { loadMailEnabled, mailEnabled, registerAvailable } = useMailAuth();
const {
  open: captchaOpen,
  verifying: captchaVerifying,
  error: captchaError,
  image: captchaImage,
  prompt: captchaPrompt,
  openCaptcha,
  onComplete: onCaptchaComplete,
  refresh: refreshCaptcha,
  close: closeCaptcha,
} = useMailCaptcha();

/** 已通过校验的登录令牌（单次使用，提交后或失败后即清空） */
const loginToken = ref('');

onMounted(() => {
  loadMailEnabled();
  // 注册成功后跳转回来带上标记，给出「已注册、待管理员分配角色」的明确反馈
  if (route.query.registered === '1') {
    message.success({
      content: $t('authentication.registerSuccessDesc'),
      duration: 5,
    });
  }
});

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.usernameTip'),
      },
      fieldName: 'username',
      label: $t('authentication.username'),
      rules: z.string().min(1, { message: $t('authentication.usernameTip') }),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: $t('authentication.password'),
      },
      fieldName: 'password',
      label: $t('authentication.password'),
      rules: z.string().min(1, { message: $t('authentication.passwordTip') }),
    },
  ];
});

/**
 * 密码登录提交：先过点选人机校验拿登录令牌，再调登录接口。
 * 用户取消校验则不提交；登录失败（含密码错误、令牌失效）清空令牌，下次重新校验。
 */
async function handleSubmit(values: Recordable<any>) {
  if (!loginToken.value) {
    try {
      loginToken.value = await openCaptcha('login');
    } catch {
      return;
    }
  }
  try {
    await authStore.authLogin({ ...values, captcha: loginToken.value });
  } catch {
    loginToken.value = '';
  }
}
</script>

<template>
  <!--
    二维码登录无后端支撑（框架 demo 空壳），扫码场景统一由第三方 OAuth 承担；
    邮箱验证码登录依赖 SMTP，自助注册还要额外依赖 serverpanel.register.enabled，
    两者都由后端 /auth/mail/enabled 决定，未就绪时入口不出现。
  -->
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="mailEnabled"
    :show-qrcode-login="false"
    :show-register="registerAvailable"
    @submit="handleSubmit"
  >
    <!--
      覆盖框架默认的第三方登录区块：原组件是 4 个没有点击事件的占位图标，
      替换为按后端已启用平台动态渲染的业务组件（一个平台都没启用时整块自动隐藏）。
    -->
    <template #third-party-login>
      <ThirdPartyLoginPanel />
    </template>
  </AuthenticationLogin>

  <!-- 登录前点选人机校验弹窗 -->
  <CaptchaModal
    :error="captchaError"
    :image="captchaImage"
    :open="captchaOpen"
    :prompt="captchaPrompt"
    :verifying="captchaVerifying"
    purpose="login"
    @close="closeCaptcha"
    @complete="onCaptchaComplete"
    @refresh="refreshCaptcha"
  />
</template>

<style scoped>
/*
 * 次级登录入口只剩「邮箱验证码登录」时，框架给它的是 w-1/2（原本两个按钮并排），
 * 这里补满宽度。用 :deep 而不是改 packages/effects 里的 AuthenticationLogin ——
 * 框架组件不动，保证后续升级不冲突。
 */
:deep(.mb-2 > .w-1\/2) {
  width: 100%;
}
</style>
