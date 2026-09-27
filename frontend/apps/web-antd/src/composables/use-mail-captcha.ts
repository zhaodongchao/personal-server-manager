import { ref } from 'vue';

import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { getCaptchaTokenApi, verifyCaptchaApi } from '#/api';

/**
 * 发信人机校验的共享逻辑（注册页与邮箱登录页共用）。
 *
 * 流程：openCaptcha() 领取挑战令牌并弹出滑块弹窗 → 用户拖动完成触发 onSuccess(time)
 * → 调 /auth/captcha/slider/verify 换取一次性发信令牌 → resolve 给调用方 → 调用方再发码。
 * openCaptcha 返回 Promise，只有校验通过才 resolve(sendToken)，否则 reject，
 * 保证 VbenPinInput 在人机校验未完成前不会误启动 60 秒倒计时。
 */
export function useMailCaptcha() {
  /** 弹窗可见性 */
  const open = ref(false);
  /** 校验中（已拖动完成、正在换令牌） */
  const verifying = ref(false);
  /** 校验失败提示 */
  const error = ref('');
  /** 每次打开自增，用于强制重挂载滑块组件（清掉上一次「已通过」状态） */
  const nonce = ref(0);

  let captchaToken = '';
  let resolveFn: (token: string) => void = () => {};
  let rejectFn: () => void = () => {};

  /** 打开滑块弹窗，返回发信令牌（校验通过时） */
  function openCaptcha(): Promise<string> {
    error.value = '';
    verifying.value = false;
    nonce.value += 1;
    open.value = true;
    return new Promise<string>((resolve, reject) => {
      resolveFn = resolve;
      rejectFn = reject;
      getCaptchaTokenApi()
        .then((data) => {
          captchaToken = data.captchaToken;
        })
        .catch((err) => {
          open.value = false;
          console.warn('[mail-captcha] 领取挑战令牌失败：', err);
          reject();
        });
    });
  }

  /** 滑块拖动完成回调（time 为拖拽时长，秒） */
  async function onSuccess(time: number) {
    if (verifying.value) {
      return;
    }
    verifying.value = true;
    error.value = '';
    try {
      const data = await verifyCaptchaApi(captchaToken, time);
      open.value = false;
      resolveFn(data.sendToken);
    } catch (err) {
      error.value = $t('authentication.captchaVerifyFailed');
      console.warn('[mail-captcha] 人机校验失败：', err);
      // 关闭并拒绝：由调用方决定是否重试（重试会重新 openCaptcha 领新令牌）
      open.value = false;
      rejectFn();
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
    nonce,
    onSuccess,
    open,
    openCaptcha,
    verifying,
  };
}
