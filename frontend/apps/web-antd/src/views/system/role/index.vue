<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import type { MenuApi, RoleApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createRoleApi,
  deleteRoleApi,
  getMenuTreeApi,
  getRoleMenuIdsApi,
  getRolePageApi,
  updateRoleApi,
} from '#/api';

defineOptions({ name: 'SystemRole' });

const { hasAccessByCodes } = useAccess();

const editingId = ref<null | string>(null);
/** 菜单树数据（TreeSelect 数据源，含 F 按钮） */
const menuTreeData = ref<any[]>([]);

/** 菜单树 → TreeSelect 数据 */
function toTreeSelect(nodes: MenuApi.MenuVO[]): any[] {
  return nodes.map((n) => ({
    children: n.children?.length ? toTreeSelect(n.children) : undefined,
    label: n.menuName,
    value: n.id,
  }));
}

onMounted(async () => {
  const tree = await getMenuTreeApi();
  menuTreeData.value = toTreeSelect(tree);
  // 表单创建时树数据尚未就绪，此处动态注入
  roleFormApi.updateSchema([
    { componentProps: { treeData: menuTreeData.value }, fieldName: 'menuIds' },
  ]);
});

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '角色名' },
      fieldName: 'roleName',
      label: '角色名',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    { field: 'roleName', title: '角色名' },
    { field: 'roleKey', title: '权限字符' },
    { field: 'sort', title: '排序', width: 80 },
    {
      field: 'status',
      slots: { default: 'status' },
      title: '状态',
      width: 90,
    },
    { field: 'remark', title: '备注' },
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
        const res = await getRolePageApi({
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

const [RoleForm, roleFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      fieldName: 'roleName',
      label: '角色名',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '字母开头，如 ops_viewer' },
      fieldName: 'roleKey',
      help: '编辑时不可修改',
      label: '权限字符',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0, style: 'width: 100%' },
      defaultValue: 0,
      fieldName: 'sort',
      label: '排序',
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
      component: 'Textarea',
      componentProps: { placeholder: '选填', rows: 2 },
      fieldName: 'remark',
      label: '备注',
    },
    {
      component: 'TreeSelect',
      componentProps: {
        fieldNames: { children: 'children', label: 'label', value: 'value' },
        multiple: true,
        placeholder: '不选则清空该角色全部菜单授权',
        showCheckedStrategy: 'SHOW_ALL',
        treeCheckable: true,
        treeDefaultExpandAll: true,
      },
      fieldName: 'menuIds',
      help: '勾选目录会自动带上子菜单',
      label: '菜单权限',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [RoleModal, roleModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await roleFormApi.validate();
    if (!valid) return;
    const values = await roleFormApi.getValues();
    const body: RoleApi.RoleBody = {
      menuIds: values.menuIds ?? [],
      remark: values.remark || undefined,
      roleKey: values.roleKey,
      roleName: values.roleName,
      sort: values.sort ?? 0,
      status: values.status,
    };
    roleModalApi.lock();
    try {
      if (editingId.value) {
        await updateRoleApi(editingId.value, body);
        message.success('修改成功');
      } else {
        await createRoleApi(body);
        message.success('新增成功');
      }
      roleModalApi.close();
      gridApi.query();
    } finally {
      roleModalApi.unlock();
    }
  },
});

function openCreate() {
  editingId.value = null;
  roleFormApi.resetForm();
  roleFormApi.setValues({ sort: 0, status: 1 });
  roleFormApi.updateSchema([
    { componentProps: { disabled: false }, fieldName: 'roleKey' },
  ]);
  roleModalApi.setData({ title: '新增角色' });
  roleModalApi.open();
}

async function openEdit(record: RoleApi.SysRole) {
  editingId.value = record.id;
  const menuIds = await getRoleMenuIdsApi(record.id);
  roleFormApi.resetForm();
  roleFormApi.setValues({
    menuIds,
    remark: record.remark,
    roleKey: record.roleKey,
    roleName: record.roleName,
    sort: record.sort,
    status: record.status,
  });
  roleFormApi.updateSchema([
    { componentProps: { disabled: true }, fieldName: 'roleKey' },
  ]);
  roleModalApi.setData({ title: '编辑角色' });
  roleModalApi.open();
}

function confirmDelete(record: RoleApi.SysRole) {
  Modal.confirm({
    content: `确定删除角色「${record.roleName}」？绑定该角色的用户将失去对应权限。`,
    onOk: async () => {
      await deleteRoleApi(record.id);
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
          v-if="hasAccessByCodes(['system:role:add'])"
          type="primary"
          @click="openCreate"
        >
          新增角色
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
            v-if="hasAccessByCodes(['system:role:edit'])"
            size="small"
            type="link"
            @click="openEdit(row as RoleApi.SysRole)"
          >
            编辑
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:role:delete'])"
            danger
            size="small"
            type="link"
            @click="confirmDelete(row as RoleApi.SysRole)"
          >
            删除
          </Button>
        </div>
      </template>
    </Grid>
    <RoleModal class="w-[520px]" title="角色">
      <RoleForm />
    </RoleModal>
  </Page>
</template>
