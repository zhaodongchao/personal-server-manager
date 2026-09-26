<script lang="ts" setup>
import { message, Tooltip } from 'ant-design-vue';
import { onMounted } from 'vue';

import { useOAuthProviders } from '#/composables/use-oauth';
import OAuthProviderIcon from '#/components/oauth/provider-icon.vue';

/**
 * 登录页第三方登录区块。
 *
 * 覆盖 Vben 框架 ThirdPartyLogin 的默认插槽 —— 框架那一版是 4 个「没有点击事件的
 * 占位图标」，本组件改为按后端 /auth/oauth/providers 实际启用的平台动态渲染：
 * 配置了凭证的平台才出现，一个都没配时整块（分隔线+标题+图标）不渲染。
 *
 * 布局与样式沿用框架登录区的 Tailwind 写法，保证与其它登录方式视觉一致。
 */
defineOptions({ name: 'ThirdPartyLoginPanel' });

const { loadProviders, providers, redirectToAuthorize } = useOAuthProviders();

onMounted(loadProviders);

async function handleClick(provider: string, name: string) {
  try {
    await redirectToAuthorize(provider, 'login');
  } catch {
    message.error(`跳转${name}授权失败，请稍后重试`);
  }
}
</script>

<template>
  <div v-if="providers.length > 0" class="w-full sm:mx-auto md:max-w-md">
    <div class="mt-4 flex items-center justify-between">
      <span class="w-[35%] border-b border-input dark:border-gray-600" />
      <span class="text-center text-xs text-muted-foreground uppercase">
        第三方登录
      </span>
      <span class="w-[35%] border-b border-input dark:border-gray-600" />
    </div>

    <div class="mt-4 flex flex-wrap justify-center">
      <Tooltip
        v-for="item in providers"
        :key="item.provider"
        :title="`使用 ${item.name} 登录`"
      >
        <button
          class="mb-3 flex size-10 cursor-pointer items-center justify-center rounded-full p-2 transition-all hover:bg-accent"
          type="button"
          @click="handleClick(item.provider, item.name)"
        >
          <OAuthProviderIcon :provider="item.provider" />
        </button>
      </Tooltip>
    </div>
  </div>
</template>
