<script lang="ts" setup>
import type { Recordable } from '@vben/types';
import type { VbenFormSchema } from '@vben/common-ui';

import { computed, ref } from 'vue';

import { AuthenticationCodeLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { mailLoginApi } from '#/api';
import { useMailAuth } from '#/composables/use-mail-auth';
import { useMailCaptcha } from '#/composables/use-mail-captcha';
import { useAuthStore } from '#/store';

import CaptchaModal from './components/captcha-modal.vue';

/**
 * 邮箱验证码登录。
 *
 * 账号策略「先用邮箱绑定到面板账号，再谈邮箱登录」：邮箱必须是 sys_user.email
 * 上已存在的值（由用户在个人中心绑定，或首次注册时登记），后端在发码阶段即校验
 * 邮箱是否已绑定账号，未绑定直接拒绝 —— 不泄露「该邮箱是否注册过」之外的信息，
 * 也不允许凭任意邮箱拿到可用的登录态。
 *
 * 验证码一次性消费、60 秒冷却、每日上限、错 5 次作废均由后端 MailCodeService 承担，
 * 前端只负责收集输入与展示（底层 VbenPinInput 内置发码倒计时）。
 */
defineOptions({ name: 'CodeLogin' });

const authStore = useAuthStore();
const { sendMailCode } = useMailAuth();
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

const loading = ref(false);

/** 表单实例：AuthenticationCodeLogin 经 defineExpose 暴露 getFormApi，发码需读取已填邮箱 */
const formRef = ref<InstanceType<typeof AuthenticationCodeLogin>>();

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.emailTip'),
      },
      fieldName: 'email',
      label: $t('authentication.email'),
      rules: z
        .string()
        .min(1, { message: $t('authentication.emailTip') })
        .email($t('authentication.emailValidErrorTip')),
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
      rules: z
        .string({ error: $t('authentication.codeTip', [6]) })
        .length(6, { message: $t('authentication.codeTip', [6]) }),
    },
  ];
});

/**
 * 发送登录验证码。
 *
 * 失败必须抛出：VbenPinInput 的 handleSendCode 仅在没有异常时才启动倒计时，
 * 吞掉异常会让用户以为邮件已发出而白白等待一轮冷却。
 */
async function handleSendCode() {
  const formApi = formRef.value?.getFormApi();
  const result = await formApi?.validateField('email');
  if (!result?.valid) {
    // 抛错后由组件内部 console.error 兜住；邮箱的具体错误已由表单内联展示
    throw new Error('邮箱未通过校验');
  }
  const values = await formApi?.getValues();
  const email = String(values?.email ?? '').trim();
  // 先通过点选人机校验换取发信令牌，再发码（后端缺令牌返回 1037/1038）
  const sendToken = await openCaptcha();
  await sendMailCode(email, 'login', sendToken);
}

async function handleSubmit(values: Recordable<any>) {
  loading.value = true;
  try {
    const { accessToken } = await mailLoginApi({
      code: String(values.code ?? ''),
      email: String(values.email ?? '').trim(),
    });
    // 复用密码登录的后续链路：写 token → 拉用户信息 → 跳首页（含登录成功提示）
    await authStore.authByToken(accessToken);
  } catch (error) {
    // 业务错误（如验证码错误/失效 1033）由请求层统一提示，此处不重复弹窗
    console.warn('[mail-auth] 邮箱验证码登录失败：', error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <AuthenticationCodeLogin
    ref="formRef"
    :form-schema="formSchema"
    :loading="loading"
    :sub-title="$t('authentication.codeSubtitle')"
    @submit="handleSubmit"
  />

  <!-- 发码前的点选人机校验弹窗（purpose 默认 send） -->
  <CaptchaModal
    :error="captchaError"
    :image="captchaImage"
    :open="captchaOpen"
    :prompt="captchaPrompt"
    :verifying="captchaVerifying"
    @close="closeCaptcha"
    @complete="onCaptchaComplete"
    @refresh="refreshCaptcha"
  />
</template>
