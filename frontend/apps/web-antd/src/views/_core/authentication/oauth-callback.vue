<script lang="ts" setup>
import { useRoute, useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { Spin } from 'ant-design-vue';
import { onMounted, ref } from 'vue';

import { oauthBindApi, oauthLoginApi } from '#/api';
import { useAuthStore } from '#/store';
import { useAccessStore } from '@vben/stores';

/**
 * 第三方登录回调页（/auth/oauth/callback）。
 *
 * 全平台在开发者后台登记的回调地址都指向本页（一个固定地址，不带 query），
 * 平台会把 code 与 state 追加到 URL 上 —— 生产为 hash 路由时参数落在 fragment 之后，
 * vue-router 的 route.query 依然可以正确解析。
 *
 *  Intent（login / bind）从 state 串前缀读取，由后端签发时写入：
 *  - login：code 换 accessToken → 复用 authByToken 完成登录并进首页；
 *           未绑定（业务码 1022）时提示「先账号密码登录后在个人中心绑定」并回登录页；
 *  - bind ：必须本地已有登录态（否则回登录页），完成绑定后回个人中心。
 */
defineOptions({ name: 'OAuthCallback' });

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const accessStore = useAccessStore();

const tip = ref('正在完成第三方授权，请稍候...');

/** 解析 state：形如 login:github:<random>，只取前两段语义信息 */
function parseState(state: string): { intent: string; provider: string } {
  const [intent = '', provider = ''] = (state ?? '').split(':');
  return { intent, provider };
}

/**
 * 读取后端业务码（R&lt;T&gt;.code）。
 *
 * 请求层把原始 axios error 原样抛出（见 api/request.ts），业务码在
 * error.response.data.code —— 这里的 1022 需要区分处理（回登录页而不是留在本页）。
 */
function businessCodeOf(error: unknown): number | undefined {
  const data = (error as { response?: { data?: { code?: number } } })?.response
    ?.data;
  return data?.code === undefined ? undefined : Number(data.code);
}

async function handleLogin(code: string, state: string, provider: string) {
  try {
    const { accessToken } = await oauthLoginApi(provider, { code, state });
    await authStore.authByToken(accessToken);
  } catch (error) {
    // 错误文案由请求层统一弹出（含后端返回的 message），这里只负责分流：
    // 未绑定（1022）与其它失败都退回登录页 —— 本页不是可以给人工干预的地方
    const businessCode = businessCodeOf(error);
    tip.value =
      businessCode === 1022
        ? '该第三方账号尚未绑定面板账号，正在返回登录页...'
        : '第三方登录失败，正在返回登录页...';
    await router.replace(LOGIN_PATH);
  }
}

async function handleBind(code: string, state: string, provider: string) {
  if (!accessStore.accessToken) {
    tip.value = '登录状态已失效，正在返回登录页...';
    await router.replace({ path: LOGIN_PATH, query: { redirect: '/profile' } });
    return;
  }
  try {
    await oauthBindApi(provider, { code, state });
    tip.value = '绑定成功，正在返回个人中心...';
    await router.replace('/profile');
  } catch {
    tip.value = '绑定失败，正在返回个人中心...';
    await router.replace('/profile');
  }
}

onMounted(async () => {
  const code = (route.query.code as string) ?? '';
  const state = (route.query.state as string) ?? '';
  const error = route.query.error as string | undefined;

  if (error || !code || !state) {
    // 平台侧拒绝授权（用户在授权页点了取消等）时只带 error 参数回来，
    // 这种场景没有后端请求，也就走不到请求层的统一提示，这里必须自己说清楚
    tip.value = error
      ? `第三方授权失败：${error}，正在返回登录页...`
      : '第三方授权未完成，正在返回登录页...';
    await router.replace(LOGIN_PATH);
    return;
  }

  const { intent, provider } = parseState(state);
  if (!provider) {
    tip.value = '授权状态非法，正在返回登录页...';
    await router.replace(LOGIN_PATH);
    return;
  }

  if (intent === 'bind') {
    await handleBind(code, state, provider);
  } else {
    await handleLogin(code, state, provider);
  }
});
</script>

<template>
  <div class="flex min-h-[240px] flex-col items-center justify-center gap-3">
    <Spin size="large" />
    <p class="text-sm text-muted-foreground">{{ tip }}</p>
  </div>
</template>
