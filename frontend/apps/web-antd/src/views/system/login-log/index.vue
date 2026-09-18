<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import { Page } from '@vben/common-ui';

import { Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getLoginLogPageApi } from '#/api';

defineOptions({ name: 'SystemLoginLog' });

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '用户名' },
      fieldName: 'username',
      label: '用户名',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [
          { label: '成功', value: 1 },
          { label: '失败', value: 0 },
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
    { field: 'username', title: '用户名', width: 140 },
    { field: 'ip', title: 'IP', width: 140 },
    {
      field: 'status',
      slots: { default: 'status' },
      title: '状态',
      width: 90,
    },
    { field: 'message', title: '结果' },
    {
      field: 'userAgent',
      formatter: ({ cellValue }: { cellValue: string }) =>
        cellValue ? cellValue.slice(0, 60) : '-',
      title: 'User-Agent',
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
        const res = await getLoginLogPageApi({
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
      <template #status="{ row }">
        <Tag :color="row.status === 1 ? 'success' : 'error'">
          {{ row.status === 1 ? '成功' : '失败' }}
        </Tag>
      </template>
    </Grid>
  </Page>
</template>
