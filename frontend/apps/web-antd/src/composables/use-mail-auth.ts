import { ref } from 'vue';

import { $t } from '@vben/locales';
import { message } from 'ant-design-vue';

import { getMailEnabledApi, sendMailCodeApi } from '#/api';

/**
 * 邮箱验证码登录 / 注册 的共用逻辑。
 *
 * 抽成 composable 的原因与 useOAuthProviders 一致：登录页要判断入口显隐，
 * 邮箱登录页与注册页都要发码，三处的「可用性来源」「失败是否启动倒计时」
 * 口径必须一致，分散实现很容易出现「入口显示但注册页发不了码」这类不对称缺陷。
 */
export function useMailAuth() {
  /** 后端是否已配置 SMTP（未配置时邮箱登录/注册入口整块隐藏） */
  const mailEnabled = ref(false);
  const loading = ref(false);

  async function loadMailEnabled() {
    loading.value = true;
    try {
      mailEnabled.value = (await getMailEnabledApi())?.enabled ?? false;
      if (!mailEnabled.value) {
        // 与 OAuth 同口径：界面表现与「接口失败」完全一致，靠日志区分。
        // 打出这一行 = 接口通了但后端没配 SMTP，去查 spring.mail.host / serverpanel.mail.from
        console.info(
          '[mail-auth] 后端未启用邮件服务，邮箱验证码登录与自助注册入口不渲染',
        );
      }
    } catch (error) {
      // 打出这一行 = 接口根本没通（后端未启动 / 代理未生效 / 500）
      console.warn('[mail-auth] 拉取邮件服务可用性失败：', error);
      mailEnabled.value = false;
    } finally {
      loading.value = false;
    }
  }

  /**
   * 发送邮箱验证码。
   *
   * 刻意让失败向调用方抛出：VbenPinInput 的 handleSendCode 只在无异常时才启动
   * 60 秒倒计时，吞掉异常会让用户以为已发送、白白干等（后端冷却 / 日限同样给出错误码）。
   * 业务错误提示由 request.ts 统一负责，这里只补成功提示。
   */
  async function sendMailCode(email: string, purpose: 'login' | 'register') {
    await sendMailCodeApi({ email, purpose });
    message.success($t('authentication.mailCodeSent', ['5']));
  }

  return {
    loadMailEnabled,
    loading,
    mailEnabled,
    sendMailCode,
  };
}
