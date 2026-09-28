<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';
import type { Recordable } from '@vben/types';

import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Tabs } from 'ant-design-vue';
import { VbenButton, VbenCheckbox } from '@vben-core/shadcn-ui';
import { $t } from '@vben/locales';

import { useVbenForm } from '#/adapter/form';
import { mailLoginApi } from '#/api';
import { useMailAuth } from '#/composables/use-mail-auth';
import { useMailCaptcha } from '#/composables/use-mail-captcha';
import { useAuthStore } from '#/store';

import CaptchaModal from './components/captcha-modal.vue';
import ThirdPartyLoginPanel from './components/third-party-login-panel.vue';

/**
 * 登录页：账号登录 与 验证码登录 整合到同一卡片内的 Tab 切换。
 *
 * 账号登录（Tab 1）：用户名 + 密码，登录前需先过点选人机校验拿登录令牌，随登录
 * 请求上报，后端原子消费；失败清空令牌下次重校验。
 * 验证码登录（Tab 2）：邮箱 + 验证码，与账号登录共用一套验证码体系（目的
 * purpose=login），发码前同样经过人机校验。
 */
defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const { loadMailEnabled, mailEnabled, registerAvailable, sendMailCode } =
  useMailAuth();
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

/** 当前激活的登录方式 Tab */
const activeTab = ref('account');

const accountLoading = ref(false);
const codeLoading = ref(false);

/** 记住我 的用户名缓存键（与框架 AuthenticationLogin 同规则） */
const REMEMBER_ME_KEY = `REMEMBER_ME_USERNAME_${location.hostname}`;
const rememberMe = ref(!!localStorage.getItem(REMEMBER_ME_KEY));

/** 已通过校验的登录令牌（单次使用，提交后或失败后即清空） */
const accountToken = ref('');

/* ---------------- Tab 1：账号登录 ---------------- */
const [AccountForm, accountFormApi] = useVbenForm(
  reactive({
    commonConfig: {
      hideLabel: true,
      hideRequiredMark: true,
    },
    schema: [
      {
        component: 'VbenInput',
        componentProps: {
          placeholder: $t('authentication.usernameTip'),
        },
        fieldName: 'username',
        label: $t('authentication.username'),
        rules: 'required',
      },
      {
        component: 'VbenInputPassword',
        componentProps: {
          placeholder: $t('authentication.password'),
        },
        fieldName: 'password',
        label: $t('authentication.password'),
        rules: 'required',
      },
    ] as VbenFormSchema[],
    showDefaultActions: false,
  }),
);

/**
 * 账号登录提交：先过点选人机校验拿登录令牌，再调登录接口。
 * 用户取消校验则不提交；登录失败清空令牌，下次重新校验。
 */
async function handleAccountSubmit() {
  const { valid } = await accountFormApi.validate();
  if (!valid) return;
  const values = (await accountFormApi.getValues()) as Recordable<string>;

  if (!accountToken.value) {
    try {
      accountToken.value = await openCaptcha('login');
    } catch {
      return;
    }
  }
  localStorage.setItem(
    REMEMBER_ME_KEY,
    rememberMe.value ? (values.username ?? '') : '',
  );
  accountLoading.value = true;
  try {
    await authStore.authLogin({ ...values, captcha: accountToken.value });
  } catch {
    accountToken.value = '';
  } finally {
    accountLoading.value = false;
  }
}

/* ---------------- Tab 2：验证码登录 ---------------- */
const [CodeForm, codeFormApi] = useVbenForm(
  reactive({
    commonConfig: {
      hideLabel: true,
      hideRequiredMark: true,
    },
    schema: [
      {
        component: 'VbenInput',
        componentProps: {
          placeholder: $t('authentication.emailTip'),
        },
        fieldName: 'email',
        label: $t('authentication.email'),
        rules: 'required',
      },
      {
        component: 'VbenPinInput',
        componentProps: {
          codeLength: 6,
          createText: (countdown: number) =>
            countdown > 0
              ? $t('authentication.sendText', [countdown])
              : $t('authentication.sendCode'),
          handleSendCode,
          maxTime: 60,
        },
        fieldName: 'code',
        label: $t('authentication.code'),
        rules: 'required',
      },
    ] as VbenFormSchema[],
    showDefaultActions: false,
  }),
);

/**
 * 发送登录验证码。失败必须抛出：VbenPinInput 的 handleSendCode 仅在无异常时才
 * 启动倒计时，吞掉异常会让用户以为邮件已发出而白白等待冷却。
 */
async function handleSendCode() {
  const result = await codeFormApi.validateField('email');
  if (!result?.valid) {
    throw new Error('邮箱未通过校验');
  }
  const values = (await codeFormApi.getValues()) as Recordable<string>;
  const email = String(values?.email ?? '').trim();
  // 先过点选人机校验换取发信令牌，再发码（后端缺令牌返回 1037/1038）
  const sendToken = await openCaptcha();
  await sendMailCode(email, 'login', sendToken);
}

/** 验证码登录提交 */
async function handleCodeSubmit() {
  const { valid } = await codeFormApi.validate();
  if (!valid) return;
  const values = (await codeFormApi.getValues()) as Recordable<string>;

  codeLoading.value = true;
  try {
    const { accessToken } = await mailLoginApi({
      code: String(values.code ?? ''),
      email: String(values.email ?? '').trim(),
    });
    // 复用密码登录的后续链路：写 token → 拉用户信息 → 跳首页
    await authStore.authByToken(accessToken);
  } catch (error) {
    // 业务错误（如验证码错误/失效 1033）由请求层统一提示
    console.warn('[mail-auth] 邮箱验证码登录失败：', error);
  } finally {
    codeLoading.value = false;
  }
}

onMounted(() => {
  loadMailEnabled();
  // 记住的用户名回填到账号登录表单
  const saved = localStorage.getItem(REMEMBER_ME_KEY);
  if (saved) {
    accountFormApi.setFieldValue('username', saved);
  }
  // 注册成功后跳转回来带上标记，给出「已注册、待管理员分配角色」的明确反馈
  if (route.query.registered === '1') {
    void router.replace({ query: {} });
  }
});
</script>

<template>
  <div class="login-panel">
    <Tabs
      v-model:active-key="activeTab"
      class="login-tabs"
      centered
      size="small"
    >
      <Tabs.TabPane key="account" :tab="$t('authentication.accountLogin')">
        <div @keydown.enter.prevent="handleAccountSubmit">
          <AccountForm class="mt-4" />
          <div class="mb-4 flex items-center justify-between">
            <VbenCheckbox v-model="rememberMe" name="rememberMe">
              {{ $t('authentication.rememberMe') }}
            </VbenCheckbox>
            <span
              class="vben-link text-sm font-normal"
              @click="router.push('/auth/forget-password')"
            >
              {{ $t('authentication.forgetPassword') }}
            </span>
          </div>
          <VbenButton
            :class="{ 'cursor-wait': accountLoading }"
            :loading="accountLoading"
            aria-label="login"
            class="w-full"
            size="lg"
            @click="handleAccountSubmit"
          >
            {{ $t('common.login') }}
          </VbenButton>
        </div>
      </Tabs.TabPane>

      <Tabs.TabPane
        v-if="mailEnabled"
        key="code"
        :tab="$t('authentication.mobileLogin')"
      >
        <div @keydown.enter.prevent="handleCodeSubmit">
          <CodeForm class="mt-4" />
          <VbenButton
            :class="{ 'cursor-wait': codeLoading }"
            :loading="codeLoading"
            aria-label="code-login"
            class="mt-4 w-full"
            size="lg"
            @click="handleCodeSubmit"
          >
            {{ $t('common.login') }}
          </VbenButton>
        </div>
      </Tabs.TabPane>
    </Tabs>

    <!-- 第三方登录 -->
    <ThirdPartyLoginPanel />

    <!-- 注册入口 -->
    <div v-if="registerAvailable" class="mt-4 text-center text-sm">
      {{ $t('authentication.accountTip') }}
      <span
        class="vben-link text-sm font-normal"
        @click="router.push('/auth/register')"
      >
        {{ $t('authentication.createAccount') }}
      </span>
    </div>

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
  </div>
</template>