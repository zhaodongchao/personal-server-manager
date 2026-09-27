<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed, markRaw, onMounted } from 'vue';
import { useRoute } from 'vue-router';

import { AuthenticationLogin, SliderCaptcha, z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { useMailAuth } from '#/composables/use-mail-auth';
import { useAuthStore } from '#/store';

import ThirdPartyLoginPanel from './components/third-party-login-panel.vue';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const route = useRoute();
const { loadMailEnabled, mailEnabled } = useMailAuth();

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
    {
      component: markRaw(SliderCaptcha),
      fieldName: 'captcha',
      rules: z.boolean().refine((value) => value, {
        message: $t('authentication.verifyRequiredTip'),
      }),
    },
  ];
});
</script>

<template>
  <!--
    二维码登录无后端支撑（框架 demo 空壳），扫码场景统一由第三方 OAuth 承担；
    邮箱验证码登录与自助注册均依赖 SMTP，按后端配置动态显隐（未配置时入口不出现）。
  -->
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="mailEnabled"
    :show-qrcode-login="false"
    :show-register="mailEnabled"
    @submit="authStore.authLogin"
  >
    <!--
      覆盖框架默认的第三方登录区块：原组件是 4 个没有点击事件的占位图标，
      替换为按后端已启用平台动态渲染的业务组件（一个平台都没启用时整块自动隐藏）。
    -->
    <template #third-party-login>
      <ThirdPartyLoginPanel />
    </template>
  </AuthenticationLogin>
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
