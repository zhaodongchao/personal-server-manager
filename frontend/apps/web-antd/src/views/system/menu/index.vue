<script lang="ts" setup>
import type { MenuApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createMenuApi,
  deleteMenuApi,
  getMenuTreeApi,
  updateMenuApi,
} from '#/api';

defineOptions({ name: 'SystemMenu' });

const { hasAccessByCodes } = useAccess();

const editingId = ref<null | string>(null);
const tableData = ref<MenuApi.MenuVO[]>([]);
/** 父菜单 TreeSelect 数据（仅 M/C，F 不能作为父级） */
const parentOptions = ref<any[]>([]);

const TYPE_TAG: Record<string, { color: string; text: string }> = {
  C: { color: 'processing', text: '菜单' },
  F: { color: 'warning', text: '按钮' },
  M: { color: 'success', text: '目录' },
};

async function loadTree() {
  const tree = await getMenuTreeApi();
  tableData.value = tree;
  const roots: any[] = [{ label: '根目录', value: '0' }];
  const walk = (nodes: MenuApi.MenuVO[]) => {
    for (const n of nodes) {
      if (n.menuType !== 'F') {
        roots.push({ label: n.menuName, value: n.id });
        if (n.children?.length) walk(n.children);
      }
    }
  };
  walk(tree);
  parentOptions.value = roots;
}

onMounted(loadTree);

const gridOptions: VxeTableGridOptions = {
  columns: [
    {
      field: 'menuName',
      slots: { default: 'menuName' },
      title: '菜单名称',
      treeNode: true,
      width: 220,
    },
    {
      field: 'menuType',
      slots: { default: 'menuType' },
      title: '类型',
      width: 80,
    },
    { field: 'icon', title: '图标', width: 80 },
    { field: 'routePath', title: '路由地址' },
    { field: 'component', title: '组件路径' },
    { field: 'perms', title: '权限标识' },
    { field: 'sort', title: '排序', width: 70 },
    {
      field: 'visible',
      slots: { default: 'visible' },
      title: '显示',
      width: 80,
    },
    {
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: '操作',
      width: 200,
    },
  ],
  pagerConfig: { enabled: false },
  proxyConfig: {
    ajax: {
      query: async () => {
        const tree = await getMenuTreeApi();
        return { items: tree };
      },
    },
  },
  rowConfig: { keyField: 'id' },
  treeConfig: { children: 'children', rowField: 'id' },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const [MenuForm, menuFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'ApiSelect',
      componentProps: {
        api: async () => parentOptions.value,
        placeholder: '根目录',
      },
      defaultValue: '0',
      fieldName: 'parentId',
      label: '父菜单',
      rules: 'required',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '目录', value: 'M' },
          { label: '菜单', value: 'C' },
          { label: '按钮', value: 'F' },
        ],
      },
      defaultValue: 'C',
      fieldName: 'menuType',
      label: '类型',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'menuName',
      label: '菜单名',
      rules: 'required',
    },
    {
      component: 'IconPicker',
      componentProps: { placeholder: '如 lucide:settings' },
      dependencies: {
        if: (values) => values.menuType !== 'F',
        triggerFields: ['menuType'],
      },
      fieldName: 'icon',
      label: '图标',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 /system/user' },
      dependencies: {
        if: (values) => values.menuType !== 'F',
        triggerFields: ['menuType'],
      },
      fieldName: 'routePath',
      label: '路由地址',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 /system/user/index' },
      dependencies: {
        if: (values) => values.menuType === 'C',
        triggerFields: ['menuType'],
      },
      fieldName: 'component',
      label: '组件路径',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 system:user:add' },
      dependencies: {
        if: (values) => values.menuType !== 'M',
        triggerFields: ['menuType'],
      },
      fieldName: 'perms',
      label: '权限标识',
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
          { label: '显示', value: 1 },
          { label: '隐藏', value: 0 },
        ],
      },
      defaultValue: 1,
      fieldName: 'visible',
      label: '是否显示',
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
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [MenuModal, menuModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await menuFormApi.validate();
    if (!valid) return;
    const values = await menuFormApi.getValues();
    // 表单的 dependencies 只隐藏字段、不清空旧值，这里按类型裁剪，
    // 与后端 SysMenuService.copy() 的归一化口径保持一致
    const menuType = values.menuType;
    const body: MenuApi.MenuBody = {
      component: menuType === 'C' ? values.component || undefined : undefined,
      icon: menuType === 'F' ? undefined : values.icon || undefined,
      menuName: values.menuName,
      menuType: values.menuType,
      parentId: values.parentId ?? '0',
      perms: menuType === 'M' ? undefined : values.perms || undefined,
      routePath: menuType === 'F' ? undefined : values.routePath || undefined,
      sort: values.sort ?? 0,
      status: values.status,
      visible: values.visible,
    };
    menuModalApi.lock();
    try {
      if (editingId.value) {
        await updateMenuApi(editingId.value, body);
        message.success('修改成功');
      } else {
        await createMenuApi(body);
        message.success('新增成功');
      }
      menuModalApi.close();
      await loadTree();
      gridApi.query();
    } finally {
      menuModalApi.unlock();
    }
  },
});

function openCreate(parent?: MenuApi.MenuVO) {
  editingId.value = null;
  menuFormApi.resetForm();
  menuFormApi.setValues({
    menuName: '',
    menuType: parent ? 'C' : 'M',
    parentId: parent ? parent.id : '0',
    sort: 0,
    status: 1,
    visible: 1,
  });
  menuModalApi.setData({ title: '新增菜单' });
  menuModalApi.open();
}

function openEdit(record: MenuApi.MenuVO) {
  editingId.value = record.id;
  menuFormApi.resetForm();
  menuFormApi.setValues({
    component: record.component,
    icon: record.icon,
    menuName: record.menuName,
    menuType: record.menuType,
    parentId: record.parentId,
    perms: record.perms,
    routePath: record.routePath,
    sort: record.sort,
    status: record.status,
    visible: record.visible,
  });
  menuModalApi.setData({ title: '编辑菜单' });
  menuModalApi.open();
}

function confirmDelete(record: MenuApi.MenuVO) {
  if (record.children?.length) {
    message.warning('存在子菜单，请先删除子节点');
    return;
  }
  Modal.confirm({
    content: `确定删除「${record.menuName}」？该操作不可恢复。`,
    onOk: async () => {
      await deleteMenuApi(record.id);
      message.success('删除成功');
      await loadTree();
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
          v-if="hasAccessByCodes(['system:menu:add'])"
          type="primary"
          @click="openCreate()"
        >
          新增菜单
        </Button>
      </template>
      <template #menuName="{ row }">
        <span class="flex items-center gap-1">
          <IconifyIcon v-if="row.icon" :icon="row.icon" />
          {{ row.menuName }}
        </span>
      </template>
      <template #menuType="{ row }">
        <Tag :color="TYPE_TAG[row.menuType as string]?.color">
          {{ TYPE_TAG[row.menuType as string]?.text }}
        </Tag>
      </template>
      <template #visible="{ row }">
        <Tag :color="row.visible === 1 ? 'success' : 'default'">
          {{ row.visible === 1 ? '显示' : '隐藏' }}
        </Tag>
      </template>
      <template #action="{ row }">
        <div class="flex gap-1">
          <Button
            v-if="
              row.menuType !== 'F' && hasAccessByCodes(['system:menu:add'])
            "
            size="small"
            type="link"
            @click="openCreate(row as MenuApi.MenuVO)"
          >
            新增
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:menu:edit'])"
            size="small"
            type="link"
            @click="openEdit(row as MenuApi.MenuVO)"
          >
            编辑
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:menu:delete'])"
            danger
            size="small"
            type="link"
            @click="confirmDelete(row as MenuApi.MenuVO)"
          >
            删除
          </Button>
        </div>
      </template>
    </Grid>
    <MenuModal class="w-[560px]" title="菜单">
      <MenuForm />
    </MenuModal>
  </Page>
</template>
