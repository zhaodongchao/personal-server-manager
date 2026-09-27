import type { AuthApi } from '#/api';

import { ref } from 'vue';

import { $t } from '@vben/locales';

import { getClickCaptchaApi, verifyClickCaptchaApi } from '#/api';

/**
 * 点选人机校验的共享逻辑（注册页 / 邮箱登录页 / 密码登录页共用）。
 *
 * 流程：openCaptcha(purpose) 弹窗并向后端领取验证码（图片 + 要点击的字符）
 * → 用户按序点满 → onComplete(clicks) 上报相对坐标，换取一次性令牌
 * （purpose=send 返回 sendToken，purpose=login 返回 loginToken）→ resolve 给调用方。
 *
 * openCaptcha 返回 Promise，只有后端校验通过才 resolve(token)，否则 reject，
 * 保证调用方（如 VbenPinInput、密码登录）在未通过人机校验前不会继续敏感操作。
 * 校验失败不关闭弹窗，而是自动换一张新图让用户重试（后端已对该挑战计数/作废）。
 *
 * @param purpose send=发邮件验证码（默认）；login=密码登录
 */
export function useMailCaptcha() {
  /** 弹窗可见性 */
  const open = ref(false);
  /** 校验中（已点满、正在向后端换取令牌） */
  const verifying = ref(false);
  /** 校验失败提示 */
  const error = ref('');
  /** 验证码图片（data URI） */
  const image = ref('');
  /** 需按顺序点击的目标字符 */
  const prompt = ref<string[]>([]);

  let captchaToken = '';
  let captchaPurpose: 'send' | 'login' = 'send';
  let resolveFn: (token: string) => void = () => {};
  let rejectFn: () => void = () => {};

  /** 向后端领取一张新验证码（图片 + 目标字符），并刷新挑战令牌 */
  async function loadChallenge() {
    const data = await getClickCaptchaApi();
    captchaToken = data.captchaToken;
    image.value = data.image;
    prompt.value = data.prompt;
  }

  /** 打开人机校验弹窗，返回一次性令牌（后端校验通过时） */
  function openCaptcha(purpose: 'send' | 'login' = 'send'): Promise<string> {
    captchaPurpose = purpose;
    error.value = '';
    verifying.value = false;
    image.value = '';
    prompt.value = [];
    open.value = true;
    return new Promise<string>((resolve, reject) => {
      resolveFn = resolve;
      rejectFn = reject;
      loadChallenge().catch((err) => {
        open.value = false;
        console.warn('[captcha] 领取点选验证码失败：', err);
        reject();
      });
    });
  }

  /** 换一张：重新领取验证码（校验失败后也走这里，自动换图） */
  async function refresh() {
    error.value = '';
    try {
      await loadChallenge();
    } catch (err) {
      console.warn('[captcha] 刷新点选验证码失败：', err);
    }
  }

  /** 用户点满目标字符：上报坐标换取一次性令牌 */
  async function onComplete(clicks: AuthApi.CaptchaClickPoint[]) {
    if (verifying.value) {
      return;
    }
    verifying.value = true;
    error.value = '';
    try {
      const data = await verifyClickCaptchaApi(captchaToken, clicks, captchaPurpose);
      open.value = false;
      const token =
        captchaPurpose === 'login' ? data.loginToken! : data.sendToken!;
      resolveFn(token);
    } catch (err) {
      error.value = $t('authentication.captchaVerifyFailed');
      console.warn('[captcha] 人机校验失败：', err);
      // 失败不关闭弹窗：换一张新图让用户重试
      await refresh();
    } finally {
      verifying.value = false;
    }
  }

  /** 用户取消弹窗 */
  function close() {
    if (open.value) {
      open.value = false;
      rejectFn();
    }
  }

  return {
    close,
    error,
    image,
    onComplete,
    open,
    openCaptcha,
    prompt,
    refresh,
    verifying,
  };
}
