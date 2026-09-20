<script setup lang="ts">
import type { Recordable, UserInfo } from '@vben/types';

import type { VbenFormSchema } from '#/adapter/form';

import { computed, onMounted, ref } from 'vue';

import { ProfileBaseSetting, z } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { message } from 'ant-design-vue';

import { getUserInfoApi, updateUserProfileApi } from '#/api';

const userStore = useUserStore();

const profileBaseSettingRef = ref();

const formSchema = computed((): VbenFormSchema[] => [
  {
    fieldName: 'realName',
    component: 'Input',
    label: '姓名',
    rules: 'required',
  },
  {
    fieldName: 'username',
    component: 'Input',
    componentProps: {
      disabled: true,
    },
    label: '用户名',
  },
  {
    fieldName: 'email',
    component: 'Input',
    label: '邮箱',
    rules: z.string().email({ message: '邮箱格式不正确' }).or(z.literal('')),
  },
  {
    fieldName: 'phone',
    component: 'Input',
    label: '手机号',
  },
  {
    fieldName: 'desc',
    component: 'Textarea',
    label: '个人简介',
  },
]);

onMounted(async () => {
  const data = await getUserInfoApi();
  setFormValues(data);
});

function setFormValues(data: Recordable<any>) {
  profileBaseSettingRef.value?.getFormApi()?.setValues({
    realName: data.realName,
    username: data.username,
    email: data.email,
    phone: data.phone,
    desc: data.desc,
  });
}

async function handleSubmit(values: Recordable<any>) {
  await updateUserProfileApi({
    realName: values.realName,
    email: values.email,
    phone: values.phone,
    desc: values.desc,
  });
  // 刷新本地用户信息，同步侧边栏昵称与头像
  const userInfo = (await getUserInfoApi()) as UserInfo;
  userStore.setUserInfo(userInfo);
  message.success('基本资料已更新');
}
</script>
<template>
  <ProfileBaseSetting
    ref="profileBaseSettingRef"
    :form-schema="formSchema"
    @submit="handleSubmit"
  />
</template>
