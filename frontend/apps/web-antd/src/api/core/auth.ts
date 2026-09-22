import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: string;
  }

  export interface RefreshTokenResult {
    data: string;
    status: number;
  }
}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/auth/login', data);
}

/**
 * 二级认证（step-up）：校验当前密码，通过后开启安全窗口。
 *
 * 用于 @Audit(safe = true) 的高危接口：服务端返回业务码 1010 时，
 * 由 request.ts 调用本接口完成认证并自动重放原请求。
 *
 * 用 requestClient 而不是 baseRequestClient：本项目的 token 只走 Authorization 头
 * （sa-token.is-read-cookie=false），baseRequestClient 不带该头会直接 401。
 */
export async function safeApi(password: string) {
  return requestClient.post<{ timeout: number }>('/auth/safe', { password });
}

/**
 * 刷新accessToken
 */
export async function refreshTokenApi() {
  return baseRequestClient.post<AuthApi.RefreshTokenResult>(
    '/auth/refresh',
    undefined,
    {
      withCredentials: true,
    },
  );
}

/**
 * 退出登录
 */
export async function logoutApi() {
  return baseRequestClient.post('/auth/logout', undefined, {
    withCredentials: true,
  });
}
