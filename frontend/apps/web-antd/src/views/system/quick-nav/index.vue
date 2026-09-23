<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import type { QuickNavApi } from '#/api';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createQuickNavApi,
  deleteQuickNavApi,
  getQuickNavPageApi,
  updateQuickNavApi,
} from '#/api';

defineOptions({ name: 'SystemQuickNav' });

const { hasAccessByCodes } = useAccess();

const editing = ref(false);
// 编辑中的记录主键：表单 schema 里没有 id 字段，而 setValues 默认
// filterFields=true，会把 schema 之外的键丢掉 —— id 必须单独持有，
// 否则提交时请求体没有 id，PUT 会被后端以 400 拒掉。
const editingId = ref<string>();

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '名称或备注' },
      fieldName: 'keyword',
      label: '关键字',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    { field: 'displayName', title: '名称', minWidth: 120 },
    {
      field: 'icon',
      slots: { default: 'icon' },
      title: '图标',
      width: 90,
    },
    { field: 'port', title: '端口', width: 90 },
    { field: 'path', title: '路径', width: 120 },
    { field: 'sort', title: '排序', width: 70 },
    {
      field: 'status',
      slots: { default: 'status' },
      title: '状态',
      width: 80,
    },
    { field: 'remark', title: '备注', minWidth: 140 },
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
        const res = await getQuickNavPageApi({
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

const [QuickNavForm, quickNavFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      fieldName: 'displayName',
      label: '名称',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { min: -1, placeholder: '如 8080，未知填 -1' },
      fieldName: 'port',
      label: '端口',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 /gitlab（可选）' },
      fieldName: 'path',
      label: '路径',
    },
    {
      component: 'IconPicker',
      componentProps: {
        iconClass: 'size-4',
        placeholder: '选择图标',
        prefix: 'lucide',
      },
      defaultValue: 'lucide:app-window',
      fieldName: 'icon',
      label: '图标',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0 },
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
      componentProps: { rows: 2 },
      fieldName: 'remark',
      label: '备注',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [QuickNavModal, quickNavModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await quickNavFormApi.validate();
    if (!valid) return;
    const values = await quickNavFormApi.getValues();
    const body: QuickNavApi.QuickNav = {
      displayName: values.displayName,
      icon: values.icon || 'lucide:app-window',
      id: editing.value ? editingId.value : undefined,
      path: values.path || '',
      port: values.port ?? -1,
      remark: values.remark,
      sort: values.sort ?? 0,
      status: values.status ?? 1,
    };
    quickNavModalApi.lock();
    try {
      if (editing.value) {
        await updateQuickNavApi(body);
        message.success('修改成功');
      } else {
        await createQuickNavApi(body);
        message.success('新增成功');
      }
      quickNavModalApi.close();
      gridApi.query();
    } finally {
      quickNavModalApi.unlock();
    }
  },
});

function openCreate() {
  editing.value = false;
  editingId.value = undefined;
  quickNavFormApi.resetForm();
  quickNavFormApi.setValues({ icon: 'lucide:app-window', sort: 0, status: 1 });
  quickNavModalApi.setData({ title: '新增导航' });
  quickNavModalApi.open();
}

function openEdit(record: QuickNavApi.QuickNav & { id: string }) {
  editing.value = true;
  editingId.value = record.id;
  quickNavFormApi.resetForm();
  quickNavFormApi.setValues({
    displayName: record.displayName,
    icon: record.icon || 'lucide:app-window',
    path: record.path || '',
    port: record.port ?? -1,
    remark: record.remark,
    sort: record.sort ?? 0,
    status: record.status ?? 1,
  });
  quickNavModalApi.setData({ title: '编辑导航' });
  quickNavModalApi.open();
}

function confirmDelete(record: QuickNavApi.QuickNav & { id: string }) {
  Modal.confirm({
    content: `确定删除导航「${record.displayName}」？`,
    onOk: async () => {
      await deleteQuickNavApi(record.id);
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
          v-if="hasAccessByCodes(['system:quick-nav:add'])"
          type="primary"
          @click="openCreate"
        >
          新增导航
        </Button>
      </template>
      <template #icon="{ row }">
        <IconifyIcon :icon="row.icon || 'lucide:app-window'" class="text-lg" />
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 1 ? 'processing' : 'default'">
          {{ row.status === 1 ? '启用' : '停用' }}
        </Tag>
      </template>
      <template #action="{ row }">
        <div class="flex gap-1">
          <Button
            v-if="hasAccessByCodes(['system:quick-nav:edit'])"
            size="small"
            type="link"
            @click="openEdit(row as QuickNavApi.QuickNav & { id: string })"
          >
            编辑
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:quick-nav:delete'])"
            danger
            size="small"
            type="link"
            @click="confirmDelete(row as QuickNavApi.QuickNav & { id: string })"
          >
            删除
          </Button>
        </div>
      </template>
    </Grid>
    <QuickNavModal class="w-[520px]" title="快捷导航">
      <QuickNavForm />
    </QuickNavModal>
  </Page>
</template>
