<script lang="ts" setup>
import {
  SvgDingDingIcon,
  SvgGiteeIcon,
  SvgGithubIcon,
  SvgQQChatIcon,
  SvgWeChatIcon,
} from '@vben/icons';

import { computed, type Component } from 'vue';

/**
 * 第三方平台图标。
 *
 * 按 provider key 渲染对应平台徽标；未登记的平台回落为一个写死的首字母占位，
 * 避免出现「点了没有图标的空白按钮」。
 */
defineOptions({ name: 'OAuthProviderIcon' });

const props = withDefaults(
  defineProps<{
    provider: string;
  }>(),
  {},
);

/** provider key（小写）→ 图标组件 */
const ICON_MAP: Record<string, Component> = {
  dingtalk: SvgDingDingIcon,
  gitee: SvgGiteeIcon,
  github: SvgGithubIcon,
  qq: SvgQQChatIcon,
  wechat: SvgWeChatIcon,
};

const icon = computed(() => ICON_MAP[props.provider]);

const fallbackText = computed(() => (props.provider ?? '?').slice(0, 2));
</script>

<template>
  <component :is="icon" v-if="icon" class="size-full" />
  <span v-else class="text-xs font-semibold uppercase">{{ fallbackText }}</span>
</template>
