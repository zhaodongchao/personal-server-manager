<script lang="ts" setup>
import type { VbenFormProps } from '@vben/common-ui';

import type { ConfigApi } from '#/api';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, message, Modal, Tag } from 'ant-design-vue';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { useVbenForm } from '#/adapter/form';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createConfigApi,
  deleteConfigApi,
  getConfigPageApi,
  updateConfigApi,
} from '#/api';

defineOptions({ name: 'SystemConfig' });

const { hasAccessByCodes } = useAccess();

const editing = ref(false);

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '名称或键名' },
      fieldName: 'keyword',
      label: '关键字',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { title: '序号', type: 'seq', width: 60 },
    { field: 'configName', title: '参数名称' },
    { field: 'configKey', title: '参数键名' },
    { field: 'configValue', title: '参数键值' },
    {
      field: 'configType',
      slots: { default: 'configType' },
      title: '内置',
      width: 80,
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
        const res = await getConfigPageApi({
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

const [ConfigForm, configFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      fieldName: 'configName',
      label: '参数名称',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 monitor.interval-seconds' },
      fieldName: 'configKey',
      label: '参数键名',
      rules: 'required',
    },
    {
      component: 'Textarea',
      componentProps: { rows: 2 },
      fieldName: 'configValue',
      label: '参数键值',
      rules: 'required',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '是', value: 'Y' },
          { label: '否', value: 'N' },
        ],
      },
      defaultValue: 'N',
      fieldName: 'configType',
      help: '内置参数不可删除',
      label: '是否内置',
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

const [ConfigModal, configModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await configFormApi.validate();
    if (!valid) return;
    const values = await configFormApi.getValues();
    const body: ConfigApi.SysConfig = {
      configKey: values.configKey,
      configName: values.configName,
      configType: values.configType,
      configValue: values.configValue,
      id: editing.value ? values.id : undefined,
      remark: values.remark,
    };
    configModalApi.lock();
    try {
      if (editing.value) {
        await updateConfigApi(body);
        message.success('修改成功');
      } else {
        await createConfigApi(body);
        message.success('新增成功');
      }
      configModalApi.close();
      gridApi.query();
    } finally {
      configModalApi.unlock();
    }
  },
});

function openCreate() {
  editing.value = false;
  configFormApi.resetForm();
  configFormApi.setValues({ configType: 'N' });
  configModalApi.setData({ title: '新增参数' });
  configModalApi.open();
}

function openEdit(record: ConfigApi.SysConfig & { id: string }) {
  editing.value = true;
  configFormApi.resetForm();
  configFormApi.setValues({
    configKey: record.configKey,
    configName: record.configName,
    configType: record.configType || 'N',
    configValue: record.configValue,
    id: record.id,
    remark: record.remark,
  });
  configModalApi.setData({ title: '编辑参数' });
  configModalApi.open();
}

function confirmDelete(record: ConfigApi.SysConfig & { id: string }) {
  if (record.configType === 'Y') {
    message.warning('内置参数不可删除');
    return;
  }
  Modal.confirm({
    content: `确定删除参数「${record.configName}」？`,
    onOk: async () => {
      await deleteConfigApi(record.id);
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
          v-if="hasAccessByCodes(['system:config:edit'])"
          type="primary"
          @click="openCreate"
        >
          新增参数
        </Button>
      </template>
      <template #configType="{ row }">
        <Tag :color="row.configType === 'Y' ? 'processing' : 'default'">
          {{ row.configType === 'Y' ? '内置' : '用户' }}
        </Tag>
      </template>
      <template #action="{ row }">
        <div class="flex gap-1">
          <Button
            v-if="hasAccessByCodes(['system:config:edit'])"
            size="small"
            type="link"
            @click="openEdit(row as ConfigApi.SysConfig & { id: string })"
          >
            编辑
          </Button>
          <Button
            v-if="hasAccessByCodes(['system:config:delete'])"
            danger
            size="small"
            type="link"
            @click="confirmDelete(row as ConfigApi.SysConfig & { id: string })"
          >
            删除
          </Button>
        </div>
      </template>
    </Grid>
    <ConfigModal class="w-[520px]" title="参数配置">
      <ConfigForm />
    </ConfigModal>
  </Page>
</template>
