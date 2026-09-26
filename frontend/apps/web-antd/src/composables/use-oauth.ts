import { ref } from 'vue';

import type { OAuthApi } from '#/api';
import { getOAuthAuthorizeApi, getOAuthProvidersApi } from '#/api';

/**
 * 第三方登录（OAuth）页面逻辑复用。
 *
 * 两个入口共用：登录页徽标区、个人中心「第三方账号」tab。
 * 之所以抽成 composable 而不是复制两份：provider 列表来源、跳转方式
 * （后端下发 authorizeUrl + window.location.href）、失败提示口径必须一致，
 * 分散实现很容易出现「登录页能跳、个人中心点了没反应」这类不对称缺陷。
 */
export function useOAuthProviders() {
  /** 后端实际启用的平台（未配置凭证的不返回） */
  const providers = ref<OAuthApi.Provider[]>([]);
  const loading = ref(false);

  async function loadProviders() {
    loading.value = true;
    try {
      providers.value = (await getOAuthProvidersApi()) ?? [];
    } catch {
      // 请求层已统一提示；此处退化为空列表 —— 未启用第三方登录时整块不渲染
      providers.value = [];
    } finally {
      loading.value = false;
    }
  }

  /**
   * 跳第三方授权页。
   *
   * 授权地址由后端拼装（含 state 与 redirect_uri），前端不参与凭证与回调地址的构造 ——
   * 回调地址全平台统一登记一个，属于后端配置资产，不应由前端传入（防 open redirect）。
   */
  async function redirectToAuthorize(
    provider: string,
    intent: 'bind' | 'login' = 'login',
  ) {
    const { authorizeUrl } = await getOAuthAuthorizeApi(provider, intent);
    window.location.href = authorizeUrl;
  }

  return {
    loadProviders,
    loading,
    providers,
    redirectToAuthorize,
  };
}
