<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import { Page } from '@vben/common-ui';

import { Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getAuditLogPageApi } from '#/api';

defineOptions({ name: 'SystemAuditLog' });

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '操作人' },
      fieldName: 'operator',
      label: '操作人',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: '系统管理', value: 'system' },
          { label: '文件管理', value: 'file' },
          { label: '运维工具', value: 'ops' },
          { label: '应用栈', value: 'appstack' },
        ],
        placeholder: '模块',
      },
      fieldName: 'module',
      label: '模块',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    { field: 'module', title: '模块', width: 100 },
    { field: 'action', title: '操作', width: 160 },
    { field: 'operator', title: '操作人', width: 120 },
    { field: 'method', title: '方法', width: 90 },
    { field: 'uri', title: 'URI', minWidth: 220 },
    { field: 'ip', title: 'IP', width: 130 },
    {
      field: 'risky',
      slots: { default: 'risky' },
      title: '风险',
      width: 80,
    },
    {
      field: 'costMs',
      formatter: ({ cellValue }: { cellValue: number }) =>
        cellValue == null ? '-' : `${cellValue}ms`,
      title: '耗时',
      width: 90,
    },
    {
      field: 'createdAt',
      formatter: ({ cellValue }: { cellValue: string }) =>
        cellValue ? cellValue.replace('T', ' ').slice(0, 19) : '-',
      title: '时间',
      width: 160,
    },
  ],
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const res = await getAuditLogPageApi({
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

const [Grid] = useVbenVxeGrid({ formOptions, gridOptions });
</script>

<template>
  <Page>
    <Grid>
      <template #risky="{ row }">
        <Tag :color="row.risky === 1 ? 'error' : 'default'">
          {{ row.risky === 1 ? '高危' : '普通' }}
        </Tag>
      </template>
    </Grid>
  </Page>
</template>
