/**
 * 该文件可自行根据业务逻辑进行调整
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import {
  authenticateResponseInterceptor,
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
  RequestClient,
} from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { message } from 'ant-design-vue';

import { useAuthStore } from '#/store';

import { refreshTokenApi } from './core';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({
    ...options,
    baseURL,
  });

  /**
   * 重新认证逻辑
   */
  async function doReAuthenticate() {
    console.warn('Access token or refresh token is invalid or expired. ');
    const accessStore = useAccessStore();
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);
    if (
      preferences.app.loginExpiredMode === 'modal' &&
      accessStore.isAccessChecked
    ) {
      accessStore.setLoginExpired(true);
    } else {
      await authStore.logout();
    }
  }

  /**
   * 刷新token逻辑
   */
  async function doRefreshToken() {
    const accessStore = useAccessStore();
    const resp = await refreshTokenApi();
    const newToken = resp.data;
    accessStore.setAccessToken(newToken);
    return newToken;
  }

  function formatToken(token: null | string) {
    return token ? `Bearer ${token}` : null;
  }

  // 请求头处理
  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();

      config.headers.Authorization = formatToken(accessStore.accessToken);
      config.headers['Accept-Language'] = preferences.app.locale;
      return config;
    },
  });

  // 处理返回的响应数据格式
  client.addResponseInterceptor(
    defaultResponseInterceptor({
      codeField: 'code',
      dataField: 'data',
      successCode: 0,
    }),
  );

  // token过期的处理
  client.addResponseInterceptor(
    authenticateResponseInterceptor({
      client,
      doReAuthenticate,
      doRefreshToken,
      enableRefreshToken: preferences.app.enableRefreshToken,
      formatToken,
    }),
  );

  // 业务码 401 兜底处理。
  // 服务端统一以 HTTP 200 返回业务错误码（R<T>.code 在响应体里），而 Vben 的
  // authenticateResponseInterceptor 只在 HTTP 状态码为 401 时触发重新认证，
  // 因此 token 失效时（HTTP 200 + code=401）不会登出：路由守卫中的
  // fetchUserInfo 抛异常会导致导航被中断，页面永久停留在启动 loading，
  // 且刷新无效（失效 token 仍在本地存储），只能手动清理缓存才能恢复。
  // 这里显式识别业务码并触发重新认证；用标志位避免 logout 请求自身
  // 再次触发该逻辑造成递归。
  let isHandlingBusinessUnauthorized = false;
  client.addResponseInterceptor({
    rejected: async (error) => {
      const businessCode = error?.response?.data?.code;
      if (Number(businessCode) === 401 && !isHandlingBusinessUnauthorized) {
        isHandlingBusinessUnauthorized = true;
        try {
          await doReAuthenticate();
        } finally {
          isHandlingBusinessUnauthorized = false;
        }
      }
      throw error;
    },
  });

  // 通用的错误处理,如果没有进入上面的错误处理逻辑，就会进入这里
  client.addResponseInterceptor(
    errorMessageResponseInterceptor((msg: string, error) => {
      // 业务码 401 已由上面的拦截器触发重新登录，不再重复弹出错误提示
      if (Number(error?.response?.data?.code) === 401) {
        return;
      }
      // 这里可以根据业务进行定制,你可以拿到 error 内的信息进行定制化处理，根据不同的 code 做不同的提示，而不是直接使用 message.error 提示 msg
      // 当前mock接口返回的错误字段是 error 或者 message
      const responseData = error?.response?.data ?? {};
      const errorMessage = responseData?.error ?? responseData?.message ?? '';
      // 如果没有错误信息，则会根据状态码进行提示
      message.error(errorMessage || msg);
    }),
  );

  return client;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
  // 开发环境数据库/Redis 经 SSH 隧道访问远程服务器，单次往返约 1.7s，
  // 登录等串行多次 IO 的接口耗时可达 10s+，默认 10s 超时会导致请求被中止
  timeout: 30_000,
});

export const baseRequestClient = new RequestClient({ baseURL: apiURL, timeout: 30_000 });
