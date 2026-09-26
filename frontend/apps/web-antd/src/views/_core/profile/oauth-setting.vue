<script lang="ts" setup>
import { message, Popconfirm, Tooltip } from 'ant-design-vue';
import { computed, onMounted, ref } from 'vue';

import dayjs from 'dayjs';

import type { OAuthApi } from '#/api';
import { getOAuthBindingsApi, unbindOAuthApi } from '#/api';
import OAuthProviderIcon from '#/components/oauth/provider-icon.vue';
import { useOAuthProviders } from '#/composables/use-oauth';

/**
 * 个人中心 - 第三方账号绑定。
 *
 * 账号策略「先绑定后登录」的管理入口：这里只列后端已启用的平台，
 * 每行一个卡片 —— 已绑定的展示第三方昵称与绑定时间并提供解绑，
 * 未绑定的给出「绑定」按钮，点击后跳第三方授权页，回调落到 /auth/oauth/callback。
 *
 * 为什么只展示已启用平台：没配凭证的平台（如未申请企业资质的微信/QQ）点了必然失败，
 * 与其让用户点错，不如不出现。
 */
defineOptions({ name: 'ProfileOauthSetting' });

const { loadProviders, providers, redirectToAuthorize } = useOAuthProviders();

const bindings = ref<OAuthApi.Binding[]>([]);
const loading = ref(true);

/** 已绑定 provider key 集合 */
const boundKeys = computed(
  () => new Set(bindings.value.map((item) => item.provider)),
);

async function loadBindings() {
  loading.value = true;
  try {
    bindings.value = (await getOAuthBindingsApi()) ?? [];
  } catch (error) {
    // 与「尚未绑定任何平台」在界面上表现一致，靠日志区分是接口没通还是真的没绑
    console.warn('[oauth] 拉取已绑定列表失败：', error);
    bindings.value = [];
  } finally {
    loading.value = false;
  }
}

function bindingOf(provider: string) {
  return bindings.value.find((item) => item.provider === provider);
}

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '';
}

async function handleBind(provider: string, name: string) {
  try {
    await redirectToAuthorize(provider, 'bind');
  } catch {
    message.error(`跳转${name}授权失败，请稍后重试`);
  }
}

async function handleUnbind(provider: string, name: string) {
  try {
    await unbindOAuthApi(provider);
    message.success(`已解除${name}绑定`);
    await loadBindings();
  } catch {
    // 失败原因（未绑定 404 / 平台未启用 1020）已由请求层统一提示
  }
}

onMounted(async () => {
  await Promise.all([loadProviders(), loadBindings()]);
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <p class="text-sm text-muted-foreground">
      绑定后可在登录页一键登录；账号密码登录方式始终可用，解绑不会导致账号失联。
    </p>

    <div v-if="loading" class="py-6 text-center text-sm text-muted-foreground">
      加载中...
    </div>

    <div
      v-for="item in providers"
      v-else
      :key="item.provider"
      class="flex items-center justify-between rounded-lg border border-input px-4 py-3"
    >
      <div class="flex items-center gap-3">
        <div class="flex size-9 items-center justify-center">
          <OAuthProviderIcon :provider="item.provider" />
        </div>
        <div class="flex flex-col">
          <span class="text-sm font-medium">{{ item.name }}</span>
          <span class="text-xs text-muted-foreground">
            <template v-if="boundKeys.has(item.provider)">
              已绑定 {{ bindingOf(item.provider)?.nickname || '（未返回昵称）' }}
              <template v-if="bindingOf(item.provider)?.boundAt">
                · {{ formatTime(bindingOf(item.provider)?.boundAt) }}
              </template>
            </template>
            <template v-else>未绑定</template>
          </span>
        </div>
      </div>

      <Tooltip
        v-if="!boundKeys.has(item.provider)"
        :title="`绑定后可使用${item.name}登录`"
      >
        <a @click="handleBind(item.provider, item.name)">绑定</a>
      </Tooltip>
      <Popconfirm
        v-else
        :title="`确定解除${item.name}绑定？`"
        ok-text="解除绑定"
        cancel-text="取消"
        @confirm="handleUnbind(item.provider, item.name)"
      >
        <a class="text-destructive">解绑</a>
      </Popconfirm>
    </div>

    <p
      v-if="!loading && providers.length === 0"
      class="py-6 text-center text-sm text-muted-foreground"
    >
      未启用任何第三方登录（后台未配置平台凭证）
    </p>
  </div>
</template>
