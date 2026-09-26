import { requestClient } from '#/api/request';

export namespace OAuthApi {
  /** 已启用的第三方平台 */
  export interface Provider {
    /** 平台标识（小写 key），用于 URL 与后端配置对应 */
    provider: string;
    /** 展示名（如 Gitee / GitHub / 钉钉） */
    name: string;
  }

  /** 授权跳转地址 */
  export interface AuthorizeResult {
    authorizeUrl: string;
  }

  /** 第三方登录结果 */
  export interface LoginResult {
    accessToken: string;
  }

  /** 已绑定的第三方身份 */
  export interface Binding {
    avatar: string;
    /** 绑定时间 */
    boundAt: string;
    nickname: string;
    name: string;
    provider: string;
  }

  /** 回调入参：平台回调页 URL 上取回的授权码与防伪串 */
  export interface CallbackParams {
    code: string;
    state: string;
  }
}

/**
 * 已启用的第三方平台列表。
 *
 * 返回后端实际配置了凭证的平台（未配置的自动隐藏），前端据此动态渲染登录页图标。
 */
export async function getOAuthProvidersApi() {
  return requestClient.get<OAuthApi.Provider[]>('/auth/oauth/providers');
}

/**
 * 获取第三方授权跳转地址。
 *
 * @param provider 平台标识（gitee / github / dingtalk / wechat / qq）
 * @param intent   login=登录场景；bind=绑定场景（后端要求登录态）
 */
export async function getOAuthAuthorizeApi(
  provider: string,
  intent: 'bind' | 'login' = 'login',
) {
  return requestClient.get<OAuthApi.AuthorizeResult>(
    `/auth/oauth/${provider}/authorize`,
    { params: { intent } },
  );
}

/** 第三方登录：回调页提交 code+state 换 accessToken（仅对已绑定用户放行） */
export async function oauthLoginApi(
  provider: string,
  data: OAuthApi.CallbackParams,
) {
  return requestClient.post<OAuthApi.LoginResult>(
    `/auth/oauth/${provider}/login`,
    data,
  );
}

/** 绑定当前登录用户的第三方身份（个人中心发起，需登录态） */
export async function oauthBindApi(
  provider: string,
  data: OAuthApi.CallbackParams,
) {
  return requestClient.post(`/auth/oauth/${provider}/bind`, data);
}

/** 当前登录用户已绑定的第三方身份列表 */
export async function getOAuthBindingsApi() {
  return requestClient.get<OAuthApi.Binding[]>('/auth/oauth/bindings');
}

/** 解绑当前用户在指定平台的第三方身份 */
export async function unbindOAuthApi(provider: string) {
  return requestClient.delete(`/auth/oauth/${provider}/binding`);
}
