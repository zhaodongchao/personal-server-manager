<script setup lang="ts">
import { computed } from 'vue';

import { ProfileSecuritySetting } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

const userStore = useUserStore();

/** 手机号脱敏：保留前 3 后 4 */
function maskPhone(phone?: string) {
  if (!phone) {
    return '未绑定';
  }
  return phone.length >= 7
    ? `${phone.slice(0, 3)}****${phone.slice(-4)}`
    : phone;
}

/** 邮箱脱敏：保留域名与首字符 */
function maskEmail(email?: string) {
  if (!email) {
    return '未绑定';
  }
  const [name, domain] = email.split('@');
  if (!domain) {
    return email;
  }
  return `${(name ?? '').slice(0, 1) || '*' }***@${domain}`;
}

const userInfo = computed(() => userStore.userInfo);

const formSchema = computed(() => [
  {
    value: true,
    fieldName: 'accountPassword',
    label: '账户密码',
    description: '当前密码强度：强',
  },
  {
    value: !!userInfo.value?.phone,
    fieldName: 'securityPhone',
    label: '密保手机',
    description: `已绑定手机：${maskPhone(userInfo.value?.phone)}`,
  },
  {
    value: false,
    fieldName: 'securityQuestion',
    label: '密保问题',
    description: '未设置密保问题，密保问题可有效保护账户安全',
  },
  {
    value: !!userInfo.value?.email,
    fieldName: 'securityEmail',
    label: '备用邮箱',
    description: `已绑定邮箱：${maskEmail(userInfo.value?.email)}`,
  },
  {
    value: false,
    fieldName: 'securityMfa',
    label: 'MFA 设备',
    description: '未绑定 MFA 设备，绑定后，可以进行二次确认',
  },
]);
</script>
<template>
  <ProfileSecuritySetting :form-schema="formSchema" />
</template>
