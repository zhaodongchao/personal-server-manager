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
    fieldName: 'gender',
    component: 'Select',
    componentProps: {
      options: [
        { label: '未知', value: 0 },
        { label: '男', value: 1 },
        { label: '女', value: 2 },
      ],
    },
    label: '性别',
  },
  {
    fieldName: 'avatar',
    component: 'AvatarPicker',
    componentProps: {
      presetCount: 8,
    },
    help: '可选择默认头像，或上传图片（自动压缩）',
    label: '头像',
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
    gender: data.gender ?? 0,
    // 用入库原始值回显：null=默认 / preset:N=预设高亮 / data:...(自定义图)
    avatar: data.avatarRaw ?? null,
    desc: data.desc,
  });
}

async function handleSubmit(values: Recordable<any>) {
  await updateUserProfileApi({
    realName: values.realName,
    email: values.email,
    phone: values.phone,
    gender: values.gender ?? 0,
    // null=不修改；''=恢复默认；preset:N / data:image/...;base64,...=设定
    avatar: values.avatar ?? null,
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
