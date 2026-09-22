<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Descriptions, DescriptionsItem, Drawer, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { LogApi } from '#/api';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getAuditLogPageApi } from '#/api';

defineOptions({ name: 'SystemAuditLog' });

/**
 * 模块取值 → 展示名。
 *
 * access（越权尝试）不是 @Audit(module=...) 里的取值，而是鉴权层单独记录的：
 * @SaCheckPermission 在 preHandle 就拦下请求，业务方法从未执行，因此不能由
 * @Audit 切面承担，只能单列一个模块。
 */
const MODULE_LABELS: Record<string, string> = {
  access: '越权尝试',
  appstack: '应用栈',
  file: '文件管理',
  ops: '运维工具',
  system: '系统管理',
};

/** 结果类别码 → 标签：0 成功 / 403 被拒 / 其余失败 */
function resultTag(code?: number) {
  if (code === 0) {
    return { color: 'success', text: '成功' };
  }
  if (code === 403) {
    return { color: 'error', text: '被拒' };
  }
  return { color: 'warning', text: '失败' };
}

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
        options: Object.entries(MODULE_LABELS).map(([value, label]) => ({
          label,
          value,
        })),
        placeholder: '模块',
      },
      fieldName: 'module',
      label: '模块',
    },
    {
      component: 'InputNumber',
      componentProps: {
        allowClear: true,
        placeholder: '如 6039',
        style: 'width: 100%',
      },
      fieldName: 'bizCode',
      label: '业务码',
    },
  ],
};

const detail = ref<LogApi.AuditLog>();
const detailOpen = ref(false);

function openDetail(row: LogApi.AuditLog) {
  detail.value = row;
  detailOpen.value = true;
}

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    {
      field: 'module',
      slots: { default: 'module' },
      title: '模块',
      width: 100,
    },
    { field: 'action', title: '操作', width: 150 },
    { field: 'operator', title: '操作人', width: 110 },
    {
      field: 'resultCode',
      slots: { default: 'result' },
      title: '结果',
      width: 90,
    },
    {
      field: 'bizCode',
      formatter: ({ cellValue }: { cellValue: number }) =>
        cellValue == null ? '-' : String(cellValue),
      title: '业务码',
      width: 90,
    },
    { field: 'uri', minWidth: 240, title: 'URI' },
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
    { slots: { default: 'detail' }, title: '详情', width: 80 },
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
      <template #module="{ row }">
        {{ MODULE_LABELS[row.module] ?? row.module }}
      </template>
      <template #result="{ row }">
        <Tag :color="resultTag(row.resultCode).color">
          {{ resultTag(row.resultCode).text }}
        </Tag>
      </template>
      <template #risky="{ row }">
        <Tag :color="row.risky === 1 ? 'error' : 'default'">
          {{ row.risky === 1 ? '高危' : '普通' }}
        </Tag>
      </template>
      <template #detail="{ row }">
        <a @click="openDetail(row)">详情</a>
      </template>
    </Grid>

    <Drawer v-model:open="detailOpen" :footer="null" :width="680" title="审计详情">
      <Descriptions v-if="detail" :column="1" bordered size="small">
        <DescriptionsItem label="模块">
          {{ MODULE_LABELS[detail.module] ?? detail.module }}
        </DescriptionsItem>
        <DescriptionsItem label="动作">{{ detail.action }}</DescriptionsItem>
        <DescriptionsItem label="操作人">{{ detail.operator }}</DescriptionsItem>
        <DescriptionsItem label="结果">
          {{ resultTag(detail.resultCode).text }}（result_code =
          {{ detail.resultCode }}）
        </DescriptionsItem>
        <DescriptionsItem label="业务码">
          {{ detail.bizCode == null ? '—' : detail.bizCode }}
        </DescriptionsItem>
        <DescriptionsItem label="属性">
          <Tag :color="detail.risky === 1 ? 'error' : 'default'">
            {{ detail.risky === 1 ? '高危' : '普通' }}
          </Tag>
        </DescriptionsItem>
        <DescriptionsItem label="请求">
          {{ detail.requestMethod }} {{ detail.uri }}
        </DescriptionsItem>
        <DescriptionsItem label="方法">{{ detail.method }}</DescriptionsItem>
        <DescriptionsItem label="耗时">
          {{ detail.costMs == null ? '—' : `${detail.costMs}ms` }}
        </DescriptionsItem>
        <DescriptionsItem label="IP">{{ detail.ip }}</DescriptionsItem>
        <DescriptionsItem label="User-Agent">
          {{ detail.userAgent }}
        </DescriptionsItem>
        <DescriptionsItem label="时间">
          {{ detail.createdAt?.replace('T', ' ').slice(0, 19) }}
        </DescriptionsItem>
        <DescriptionsItem label="错误信息">
          <span style="color: #cf1322">{{ detail.error || '—' }}</span>
        </DescriptionsItem>
        <DescriptionsItem label="入参">
          <pre
            style="
              margin: 0;
              white-space: pre-wrap;
              word-break: break-all;
            "
            >{{ detail.params || '—' }}</pre
          >
        </DescriptionsItem>
      </Descriptions>
    </Drawer>
  </Page>
</template>
