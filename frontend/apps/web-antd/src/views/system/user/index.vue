<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import type { UserApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createUserApi,
  deleteUserApi,
  getRoleAllApi,
  getUserPageApi,
  getUserRoleIdsApi,
  updateUserApi,
} from '#/api';

defineOptions({ name: 'SystemUser' });

const { hasAccessByCodes } = useAccess();

const roleOptions = ref<{ label: string; value: string }[]>([]);
const editingId = ref<null | string>(null);

onMounted(async () => {
  const roles = await getRoleAllApi();
  roleOptions.value = roles.map((r) => ({
    label: `${r.roleName} (${r.roleKey})`,
    value: r.id,
  }));
});

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { placeholder: '用户名', allowClear: true },
      fieldName: 'username',
      label: '用户名',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: '启用', value: 1 },
          { label: '停用', value: 0 },
        ],
        placeholder: '状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    { field: 'username', title: '用户名' },
    { field: 'nickname', title: '昵称' },
    { field: 'email', title: '邮箱' },
    { field: 'phone', title: '手机号' },
    {
      field: 'status',
      slots: { default: 'status' },
      title: '状态',
      width: 90,
    },
    {
      field: 'lastLoginAt',
      formatter: ({ cellValue }: { cellValue: string }) =>
        cellValue ? cellValue.replace('T', ' ').slice(0, 19) : '-',
      title: '最后登录',
      width: 160,
    },
    { field: 'lastLoginIp', title: '登录IP', width: 130 },
    {
      field: 'createdAt',
      formatter: ({ cellValue }: { cellValue: string }) =>
        cellValue ? cellValue.replace('T', ' ').slice(0, 19) : '-',
      title: '创建时间',
      width: 160,
    },
    {
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: '操作',
      width: 140,
    },
  ],
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const res = await getUserPageApi({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        });
        return { items: res.records ?? [], total: res.total ?? 0 };
      },
    },
  },
  rowConfig: { keyField: 'id' },
};

const [Grid, gridApi] = useVbenVxeGrid({ formOptions, gridOptions });

const [UserForm, userFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      componentProps: { placeholder: '字母开头，3-30 位' },
      fieldName: 'username',
      help: '编辑时不可修改',
      label: '用户名',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'nickname',
      label: '昵称',
      rules: 'required',
    },
    {
      component: 'InputPassword',
      componentProps: {
        autocomplete: 'new-password',
        placeholder: '新增必填；编辑留空表示不修改',
      },
      fieldName: 'password',
      label: '密码',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '选填' },
      fieldName: 'email',
      label: '邮箱',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '选填' },
      fieldName: 'phone',
      label: '手机号',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '启用', value: 1 },
          { label: '停用', value: 0 },
        ],
      },
      defaultValue: 1,
      fieldName: 'status',
      label: '状态',
    },
    {
      component: 'ApiSelect',
      componentProps: {
        api: async () => roleOptions.value,
        mode: 'multiple',
        placeholder: '请选择角色',
      },
      fieldName: 'roleIds',
      label: '角色',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [UserModal, userModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await userFormApi.validate();
    if (!valid) return;
    const values = await userFormApi.getValues();
    const body: UserApi.UserBody = {
      email: values.email || undefined,
      nickname: values.nickname,
      password: values.password || undefined,
      phone: values.phone || undefined,
      roleIds: values.roleIds ?? [],
      status: values.status,
      username: values.username,
    };
    userModalApi.lock();
    try {
      if (editingId.value) {
        await updateUserApi(editingId.value, body);
        message.success('修改成功');
      } else {
        await createUserApi(body);
        message.success('新增成功');
      }
      userModalApi.close();
      gridApi.query();
    } finally {
      userModalApi.unlock();
    }
  },
});

async function openCreate() {
  editingId.value = null;
  userFormApi.resetForm();
  userFormApi.setValues({ status: 1 });
  userFormApi.updateSchema([
    { componentProps: { disabled: false }, fieldName: 'username' },
  ]);
  userModalApi.setData({ title: '新增用户' });
  userModalApi.open();
}

async function openEdit(record: UserApi.SysUser) {
  editingId.value = record.id;
  const roleIds = await getUserRoleIdsApi(record.id);
  userFormApi.resetForm();
  userFormApi.setValues({
    email: record.email,
    nickname: record.nickname,
    phone: record.phone,
    roleIds,
    status: record.status,
    username: record.username,
  });
  userFormApi.updateSchema([
    { componentProps: { disabled: true }, fieldName: 'username' },
  ]);
  userModalApi.setData({ title: '编辑用户' });
  userModalApi.open();
}

function confirmDelete(record: UserApi.SysUser) {
  Modal.confirm({
    content: `确定删除用户「${record.username}」？该操作不可恢复。`,
    onOk: async () => {
      await deleteUserApi(record.id);
      message.success('删除成功');
      gridApi.query();
    },
    title: '删除确认',
  });
}
</script>

<template>
  <Page>
    <Grid>
      <template #toolbar-tools>
        <Button
          v-if="hasAccessByCodes(['system:user:add'])"
          type="primary"
          @click="openCreate"
        >
          新增用户
        </Button>
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 1 ? 'success' : 'error'">
          {{ row.status === 1 ? '启用' : '停用' }}
        </Tag>
      </template>
      <template #action="{ row }">
        <div class="flex gap-1">
          <Button
            v-if="hasAccessByCodes(['system:user:edit'])"
            size="small"
            type="link"
            @click="openEdit(row as UserApi.SysUser)"
          >
            编辑
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:user:delete'])"
            danger
            size="small"
            type="link"
            @click="confirmDelete(row as UserApi.SysUser)"
          >
            删除
          </Button>
        </div>
      </template>
    </Grid>
    <UserModal class="w-[520px]" title="用户">
      <UserForm />
    </UserModal>
  </Page>
</template>
