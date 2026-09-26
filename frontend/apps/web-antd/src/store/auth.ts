import type { Recordable, UserInfo } from '@vben/types';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { notification } from 'ant-design-vue';
import { defineStore } from 'pinia';

import { getUserInfoApi, loginApi, logoutApi } from '#/api';
import { $t } from '#/locales';

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);

  /**
   * 凭 accessToken 完成登录后续链路（写 token → 拉用户信息 → 跳转 → 成功提示）。
   *
   * 从 authLogin 中抽出，供第三方登录回调页复用 —— 回调拿到的是后端直接返回的
   * accessToken，没有「账号密码登录」这一步，但后续链路完全一致。
   * 说明（2026-09-21）：原「权限码」接口 /auth/codes 已下线，登录链路不再发起
   * 该请求；按钮级可见性改由 accessMode='backend' 语义决定（见 use-access.ts），
   * 真实鉴权一律由后端接口执行。
   *
   * @param accessToken 后端签发的令牌
   * @param onSuccess   自定义跳转回调（不传则跳默认首页）
   */
  async function authByToken(
    accessToken: string,
    onSuccess?: () => Promise<void> | void,
  ) {
    accessStore.setAccessToken(accessToken);

    const fetchUserInfoResult = await fetchUserInfo();
    const currentUserInfo = fetchUserInfoResult;

    userStore.setUserInfo(currentUserInfo);
    accessStore.setAccessCodes([]);

    if (accessStore.loginExpired) {
      accessStore.setLoginExpired(false);
    } else {
      onSuccess
        ? await onSuccess?.()
        : await router.push(
            currentUserInfo.homePath || preferences.app.defaultHomePath,
          );
    }

    if (currentUserInfo?.realName) {
      notification.success({
        description: `${$t('authentication.loginSuccessDesc')}:${currentUserInfo?.realName}`,
        duration: 3,
        message: $t('authentication.loginSuccess'),
      });
    }

    return {
      userInfo: currentUserInfo,
    };
  }

  /**
   * 异步处理登录操作
   * Asynchronously handle the login process
   * @param params 登录表单数据
   */
  async function authLogin(
    params: Recordable<any>,
    onSuccess?: () => Promise<void> | void,
  ) {
    // 异步处理用户登录操作并获取 accessToken
    let userInfo: null | UserInfo = null;
    try {
      loginLoading.value = true;
      const { accessToken } = await loginApi(params);

      // 如果成功获取到 accessToken
      if (accessToken) {
        const result = await authByToken(accessToken, onSuccess);
        userInfo = result.userInfo;
      }
    } finally {
      loginLoading.value = false;
    }

    return {
      userInfo,
    };
  }

  async function logout(redirect: boolean = true) {
    try {
      await logoutApi();
    } catch {
      // 不做任何处理
    }
    resetAllStores();
    accessStore.setLoginExpired(false);

    // 回登录页带上当前路由地址
    await router.replace({
      path: LOGIN_PATH,
      query: redirect
        ? {
            redirect: encodeURIComponent(router.currentRoute.value.fullPath),
          }
        : {},
    });
  }

  async function fetchUserInfo() {
    const userInfo = await getUserInfoApi();
    userStore.setUserInfo(userInfo);
    return userInfo;
  }

  function $reset() {
    loginLoading.value = false;
  }

  return {
    $reset,
    authByToken,
    authLogin,
    fetchUserInfo,
    loginLoading,
    logout,
  };
});
