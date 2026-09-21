<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { Profile } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { message } from 'ant-design-vue';

import { getUserInfoApi, updateUserProfileApi } from '#/api';
import AvatarPicker from '#/components/avatar-picker/index.vue';

import ProfileBase from './base-setting.vue';
import ProfileNotificationSetting from './notification-setting.vue';
import ProfilePasswordSetting from './password-setting.vue';
import ProfileSecuritySetting from './security-setting.vue';

const userStore = useUserStore();

const tabsValue = ref<string>('basic');

/** 头像原始值：null=未设置 / ''=恢复默认 / preset:N / data:image/...;base64,... */
const avatarValue = ref<null | string>(null);

onMounted(async () => {
  const info = await getUserInfoApi();
  avatarValue.value = info.avatarRaw ?? null;
});

/** 顶部头像点选/上传后即时落库，并同步本地用户信息（侧边栏头像随之更新） */
async function handleAvatarChange(value: null | string) {
  avatarValue.value = value;
  await updateUserProfileApi({ avatar: value });
  userStore.setUserInfo(await getUserInfoApi());
  message.success('头像已更新');
}

const tabs = ref([
  {
    label: '基本设置',
    value: 'basic',
  },
  {
    label: '安全设置',
    value: 'security',
  },
  {
    label: '修改密码',
    value: 'password',
  },
  {
    label: '新消息提醒',
    value: 'notice',
  },
]);
</script>
<template>
  <Profile
    v-model:model-value="tabsValue"
    title="个人中心"
    :user-info="userStore.userInfo"
    :tabs="tabs"
  >
    <template #avatar>
      <AvatarPicker
        :value="avatarValue"
        compact
        @update:value="handleAvatarChange"
      />
    </template>
    <template #content>
      <ProfileBase v-if="tabsValue === 'basic'" />
      <ProfileSecuritySetting v-if="tabsValue === 'security'" />
      <ProfilePasswordSetting v-if="tabsValue === 'password'" />
      <ProfileNotificationSetting v-if="tabsValue === 'notice'" />
    </template>
  </Profile>
</template>
