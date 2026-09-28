<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';
import type { Recordable } from '@vben/types';

import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { AuthenticationRegister, z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { checkUsernameApi, registerApi } from '#/api';
import { useMailAuth } from '#/composables/use-mail-auth';
import { useMailCaptcha } from '#/composables/use-mail-captcha';

import CaptchaModal from './components/captcha-modal.vue';

/**
 * 自助注册（邮箱验证码方式）。
 *
 * 与邮箱登录共用一套验证码体系，区别只在 purpose=register：后端要求注册开关
 * （serverpanel.register.enabled）开启、且邮箱未被占用。注册成功后账号
 * **不分配任何角色** —— 能登录、但只看得到个人中心，业务权限由管理员在
 * 「系统管理-用户」里分配。这是刻意的设计：面板是服务器管理入口，注册入口
 * 即便开启，也不应让自助账号直接拿到任何运维权限。
 */
defineOptions({ name: 'Register' });

const router = useRouter();
const { sendMailCode, allowedEmailDomains, loadMailEnabled } = useMailAuth();
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

// 拉取邮件可用性 + 允许注册的邮箱域名白名单（驱动提示与预校验）；
// 与登录页同口径：composable 状态按实例隔离，注册页需自行加载
onMounted(loadMailEnabled);

/** 允许注册的邮箱域名提示（空白名单 = 不限制，不展示） */
const emailDomainTip = computed(() => {
  if (!allowedEmailDomains.value?.length) {
    return '';
  }
  return $t('authentication.registerEmailDomainTip', [
    allowedEmailDomains.value.join('、'),
  ]);
});

/** 客户端预校验邮箱域名（仅 UX，最终由后端 1036 兜底） */
function checkEmailDomain(email: string) {
  const domains = allowedEmailDomains.value;
  if (!domains?.length || !email.includes('@')) {
    return;
  }
  const domain = email.split('@')[1]?.trim().toLowerCase() ?? '';
  const matched = domains.some(
    (d) => domain === d.toLowerCase() || domain.endsWith(`.${d.toLowerCase()}`),
  );
  if (!matched) {
    throw new Error(
      $t('authentication.registerEmailDomainError', [domains.join('、')]),
    );
  }
}

/** 用户名失焦实时查重（仅提示，不阻断输入；最终唯一性由注册接口兜底） */
async function checkUsernameAvailability() {
  const formApi = formRef.value?.getFormApi();
  const values = await formApi?.getValues();
  const username = String(values?.username ?? '').trim();
  if (!username || !/^[a-zA-Z0-9_]{3,30}$/.test(username)) {
    return;
  }
  try {
    const { available } = await checkUsernameApi(username);
    if (!available) {
      message.error($t('authentication.usernameTaken'));
    }
  } catch {
    // 静默：查重接口异常不应阻断用户正常输入
  }
}

/** 表单实例：AuthenticationRegister 经 defineExpose 暴露 getFormApi，发码需读取已填邮箱 */
const formRef = ref<InstanceType<typeof AuthenticationRegister>>();

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.usernameRuleTip'),
        onBlur: checkUsernameAvailability,
      },
      fieldName: 'username',
      label: $t('authentication.username'),
      rules: z
        .string()
        .min(3, { message: $t('authentication.usernameRuleTip') })
        .max(30, { message: $t('authentication.usernameRuleTip') })
        .regex(/^[a-zA-Z0-9_]+$/, $t('authentication.usernameRuleTip')),
    },
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

/** 发送注册验证码（失败必须抛出，理由同邮箱登录页：吞异常会误启动倒计时） */
async function handleSendCode() {
  const formApi = formRef.value?.getFormApi();
  const result = await formApi?.validateField('email');
  if (!result?.valid) {
    throw new Error('邮箱未通过校验');
  }
  const values = await formApi?.getValues();
  const email = String(values?.email ?? '').trim();
  // 域名白名单（如配置）先在客户端拦截，避免无谓的邮件发送与冷却消耗
  checkEmailDomain(email);
  // 先通过点选人机校验换取发信令牌，再发码（后端缺令牌返回 1037/1038）
  const sendToken = await openCaptcha();
  await sendMailCode(email, 'register', sendToken);
}

async function handleSubmit(value: Recordable<any>) {
  loading.value = true;
  try {
    await registerApi({
      code: String(value.code ?? ''),
      email: String(value.email ?? '').trim(),
      password: String(value.password ?? ''),
      username: String(value.username ?? '').trim(),
    });
    // 注册不自动登录：账号待管理员分配角色，回登录页走邮箱验证码登录
    await router.push('/auth/login?registered=1');
  } catch (error) {
    // 业务错误（如注册未开启 1034、用户名或邮箱已存在 1035）由请求层统一提示
    console.warn('[mail-auth] 自助注册失败：', error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div>
    <AuthenticationRegister
      ref="formRef"
      :form-schema="formSchema"
      :loading="loading"
      :sub-title="$t('authentication.registerSubtitle')"
      @submit="handleSubmit"
    />
    <p
      v-if="emailDomainTip"
      class="text-muted-foreground mt-3 text-center text-sm"
    >
      {{ emailDomainTip }}
    </p>

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
