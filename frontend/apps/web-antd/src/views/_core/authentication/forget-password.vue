<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';
import type { Recordable } from '@vben/types';

import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { VbenButton } from '@vben-core/shadcn-ui';
import { z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { resetPasswordApi } from '#/api';
import { useVbenForm } from '#/adapter/form';
import { useMailAuth } from '#/composables/use-mail-auth';
import { useMailCaptcha } from '#/composables/use-mail-captcha';

import CaptchaModal from './components/captcha-modal.vue';

/**
 * 忘记密码（两步，邮箱验证码方式，与登录 / 注册共用一套验证码体系）。
 *
 * 第一步：输入邮箱 + 获取验证码（先过点选人机校验换取发信令牌，再发码 purpose=reset）并输入验证码；
 * 第二步：设置新密码 + 确认密码，提交后由后端更新密码并强制该账号全端下线。
 */
defineOptions({ name: 'ForgetPassword' });

const router = useRouter();

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

/** 当前步骤：send=申请重置；set=设置新密码 */
const step = ref<'send' | 'set'>('send');
const submitting = ref(false);

/** 第一步（邮箱 + 验证码）中携带到第二步的值 */
const emailRef = ref('');
const codeRef = ref('');

/* ---------------- 第一步：申请重置 ---------------- */
const [SendForm, sendFormApi] = useVbenForm(
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
    ] as VbenFormSchema[],
    showDefaultActions: false,
  }),
);

/** 发送重置验证码。失败必须抛出：VbenPinInput 仅在无异常时才启动倒计时 */
async function handleSendCode() {
  const result = await sendFormApi.validateField('email');
  if (!result?.valid) {
    throw new Error('邮箱未通过校验');
  }
  const values = (await sendFormApi.getValues()) as Recordable<string>;
  const email = String(values?.email ?? '').trim();
  // 先过点选人机校验换取发信令牌，再发码（后端缺令牌返回 1037/1038）
  const sendToken = await openCaptcha();
  await sendMailCode(email, 'reset', sendToken);
}

/** 第一步「下一步」：校验邮箱 + 验证码，进入设置新密码 */
async function handleNext() {
  const { valid } = await sendFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await sendFormApi.getValues()) as Recordable<string>;
  emailRef.value = String(values?.email ?? '').trim();
  codeRef.value = String(values?.code ?? '');
  step.value = 'set';
}

/* ---------------- 第二步：设置新密码 ---------------- */
const [ResetForm, resetFormApi] = useVbenForm(
          reactive({
    commonConfig: {
      hideLabel: true,
      hideRequiredMark: true,
    },
    schema: [
      {
        component: 'VbenInputPassword',
        componentProps: {
          passwordStrength: true,
          placeholder: $t('authentication.password'),
        },
        fieldName: 'password',
        label: $t('authentication.password'),
        renderComponentContent() {
          return {
            strengthText: () => $t('authentication.passwordStrength'),
          };
        },
        rules: z
          .string()
          .min(8, { message: $t('authentication.passwordRuleTip') })
          .max(64, { message: $t('authentication.passwordRuleTip') }),
      },
      {
        component: 'VbenInputPassword',
        componentProps: {
          placeholder: $t('authentication.confirmPassword'),
        },
        dependencies: {
          rules(values) {
            const { password } = values;
            return z
              .string({ error: $t('authentication.passwordRuleTip') })
              .refine((value) => value === password, {
                message: $t('authentication.confirmPasswordTip'),
              });
          },
          triggerFields: ['password'],
        },
        fieldName: 'confirmPassword',
        label: $t('authentication.confirmPassword'),
      },
    ] as VbenFormSchema[],
    showDefaultActions: false,
  }),
);

/** 第二步「重置密码」：校验新密码并提交 */
async function handleReset() {
  const { valid } = await resetFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await resetFormApi.getValues()) as Recordable<string>;
  const password = String(values?.password ?? '');

  submitting.value = true;
  try {
    await resetPasswordApi({
      code: codeRef.value,
      email: emailRef.value,
      password,
    });
    message.success($t('authentication.resetPasswordSuccess'));
    await router.push('/auth/login');
  } catch (error) {
    // 业务错误（如验证码错误/失效 1033、用户不存在 1005）由请求层统一提示
    console.warn('[mail-auth] 密码重置失败：', error);
  } finally {
    submitting.value = false;
  }
}

/** 返回登录 */
function goToLogin() {
  router.push('/auth/login');
}

/** 由设置新密码回到申请重置（修改邮箱 / 重发验证码） */
function backToSend() {
  step.value = 'send';
}
</script>

<template>
  <div>
    <section class="mb-4">
      <h2 class="text-2xl font-semibold">
        {{ $t('authentication.forgetPassword') }}
      </h2>
      <p class="mt-2 text-sm text-muted-foreground">
        {{ $t('authentication.forgetPasswordSubtitle') }}
      </p>
    </section>

    <!-- 第一步：输入邮箱 + 获取并输入验证码 -->
    <section v-if="step === 'send'">
      <SendForm class="mt-2" />
      <VbenButton class="mt-4 w-full" size="lg" @click="handleNext">
        {{ $t('authentication.next') }}
      </VbenButton>
      <VbenButton class="mt-4 w-full" variant="outline" @click="goToLogin">
        {{ $t('common.back') }}
      </VbenButton>
    </section>

    <!-- 第二步：设置新密码 -->
    <section v-else>
      <ResetForm class="mt-2" />
      <VbenButton
        :class="{ 'cursor-wait': submitting }"
        :loading="submitting"
        class="mt-4 w-full"
        size="lg"
        @click="handleReset"
      >
        {{ $t('authentication.resetPassword') }}
      </VbenButton>
      <VbenButton class="mt-4 w-full" variant="outline" @click="backToSend">
        {{ $t('common.back') }}
      </VbenButton>
    </section>

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
  </div>
</template>