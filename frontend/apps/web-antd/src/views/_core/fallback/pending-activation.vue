<script lang="ts" setup>
import { useRouter } from 'vue-router';

import { Fallback } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { Button as AButton } from 'ant-design-vue';

import { useAuthStore } from '#/store';

/**
 * 账号待激活提示页。
 *
 * 自助注册的新账号不分配任何角色，登录后业务菜单为空，若直接落到默认首页会触发
 * 404。路由守卫在检测到「已登录但无角色」时重定向到此页，给出明确说明与退出入口，
 * 避免用户面对一个毫无意义的 404。管理员在「系统管理-用户」分配角色后，
 * 用户退出重新登录即可正常进入系统。
 */
defineOptions({ name: 'PendingActivation' });

const router = useRouter();
const authStore = useAuthStore();

async function handleLogout() {
  await authStore.logout();
  // 回登录页（logout 已 replace 到登录页，这里仅为兜底/可读性）
  await router.replace('/auth/login');
}
</script>

<template>
  <Fallback
    status="coming-soon"
    :description="$t('authentication.accountPendingDesc')"
    :title="$t('authentication.accountPendingTitle')"
  >
    <template #action>
      <AButton size="large" type="primary" @click="handleLogout">
        {{ $t('authentication.accountPendingLogout') }}
      </AButton>
    </template>
  </Fallback>
</template>
