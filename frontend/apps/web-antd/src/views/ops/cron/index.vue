<script lang="ts" setup>
import type { OpsApi } from '#/api';

import { onMounted, reactive, ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, Drawer, Input, message, Modal, Space, Table, Tag } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import {
  createCronJobApi,
  deleteCronJobApi,
  getCronJobPageApi,
  getCronLogPageApi,
  runCronJobApi,
  updateCronJobApi,
} from '#/api';

defineOptions({ name: 'OpsCron' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const list = ref<OpsApi.CronJob[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const keyword = ref('');

async function load() {
  loading.value = true;
  try {
    const res = await getCronJobPageApi({
      keyword: keyword.value || undefined,
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
    });
    list.value = res.records ?? [];
    pagination.total = res.total ?? 0;
  } finally {
    loading.value = false;
  }
}

// ==================== 新建 / 编辑 ====================
const editing = ref(false);
const editingId = ref<null | string>(null);

const [JobForm, jobFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      fieldName: 'name',
      label: '任务名',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 0 2 * * * （每天 02:00）' },
      fieldName: 'cronExpr',
      label: 'cron 表达式',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '白名单命令 + 参数，空格分隔，如 systemctl restart nginx',
      },
      fieldName: 'command',
      label: '命令',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1, max: 86400, style: 'width: 100%' },
      defaultValue: 300,
      fieldName: 'timeoutSec',
      label: '超时(秒)',
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

const [JobModal, jobModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await jobFormApi.validate();
    if (!valid) {
      return;
    }
    const values = await jobFormApi.getValues();
    const body: OpsApi.CronJob = {
      command: values.command,
      cronExpr: values.cronExpr,
      id: editingId.value ?? undefined,
      name: values.name,
      remark: values.remark,
      status: values.status,
      timeoutSec: values.timeoutSec ?? 300,
    };
    jobModalApi.lock();
    try {
      if (editing.value && editingId.value) {
        await updateCronJobApi(body);
        message.success('修改成功');
      } else {
        await createCronJobApi(body);
        message.success('新增成功');
      }
      jobModalApi.close();
      await load();
    } finally {
      jobModalApi.unlock();
    }
  },
});

function openCreate() {
  editing.value = false;
  editingId.value = null;
  jobFormApi.resetForm();
  jobFormApi.setValues({ status: 1, timeoutSec: 300 });
  jobModalApi.setData({ title: '新增计划任务' });
  jobModalApi.open();
}

function openEdit(record: OpsApi.CronJob) {
  editing.value = true;
  editingId.value = record.id ?? null;
  jobFormApi.resetForm();
  jobFormApi.setValues({
    command: record.command,
    cronExpr: record.cronExpr,
    name: record.name,
    remark: record.remark,
    status: record.status,
    timeoutSec: record.timeoutSec,
  });
  jobModalApi.setData({ title: '编辑计划任务' });
  jobModalApi.open();
}

function confirmDelete(record: OpsApi.CronJob) {
  Modal.confirm({
    content: `确定删除计划任务「${record.name}」？执行日志将保留。`,
    onOk: async () => {
      await deleteCronJobApi(record.id!);
      message.success('删除成功');
      await load();
    },
    title: '删除确认',
  });
}

async function confirmRun(record: OpsApi.CronJob) {
  await runCronJobApi(record.id!);
  message.success('已触发执行，请查看执行日志');
  await load();
}

// ==================== 执行日志 ====================
const logOpen = ref(false);
const logJob = ref<OpsApi.CronJob>();
const logLoading = ref(false);
const logList = ref<OpsApi.CronLog[]>([]);
const logPagination = reactive({ current: 1, pageSize: 10, total: 0 });

async function loadLogs() {
  if (!logJob.value) {
    return;
  }
  logLoading.value = true;
  try {
    const res = await getCronLogPageApi(logJob.value.id!, {
      pageNum: logPagination.current,
      pageSize: logPagination.pageSize,
    });
    logList.value = res.records ?? [];
    logPagination.total = res.total ?? 0;
  } finally {
    logLoading.value = false;
  }
}

function openLogs(record: OpsApi.CronJob) {
  logJob.value = record;
  logPagination.current = 1;
  logOpen.value = true;
  loadLogs();
}

function formatTime(s?: string) {
  if (!s) {
    return '-';
  }
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

onMounted(load);
</script>

<template>
  <Page class="flex flex-col">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-64"
        placeholder="按任务名/命令过滤"
        @keyup.enter="load"
        @press-enter="load"
      />
      <Button type="primary" @click="load">搜索</Button>
      <Button
        v-if="hasAccessByCodes(['ops:cron:add'])"
        class="ml-auto"
        type="primary"
        @click="openCreate"
      >
        新增任务
      </Button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Table
        :data-source="list"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          total: pagination.total,
          onChange: (page: number, size: number) => {
            pagination.current = page;
            pagination.pageSize = size;
            load();
          },
        }"
        :row-key="(record: OpsApi.CronJob) => record.id ?? record.name"
        :scroll="{ x: 980 }"
        size="small"
      >
        <Table.Column key="name" title="任务名" width="150">
          <template #default="{ record }">
            <span class="font-medium">{{ record.name }}</span>
          </template>
        </Table.Column>
        <Table.Column data-index="cronExpr" title="cron 表达式" width="110" />
        <Table.Column key="command" title="命令">
          <template #default="{ record }">
            <span class="break-all font-mono text-xs">{{ record.command }}</span>
          </template>
        </Table.Column>
        <Table.Column key="status" title="状态" width="80">
          <template #default="{ record }">
            <Tag :color="record.status === 1 ? 'success' : 'default'">
              {{ record.status === 1 ? '启用' : '停用' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="last" title="上次执行" width="150">
          <template #default="{ record }">
            {{ formatTime(record.lastRunAt) }}
          </template>
        </Table.Column>
        <Table.Column key="next" title="下次执行" width="150">
          <template #default="{ record }">
            {{ formatTime(record.nextRunAt) }}
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="230" fixed="right">
          <template #default="{ record }">
            <Space :size="2" wrap>
              <Button
                v-if="hasAccessByCodes(['ops:cron:run'])"
                size="small"
                type="link"
                @click="confirmRun(record as OpsApi.CronJob)"
              >
                立即执行
              </Button>
              <Button
                v-if="hasAccessByCodes(['ops:cron:edit'])"
                size="small"
                type="link"
                @click="openEdit(record as OpsApi.CronJob)"
              >
                编辑
              </Button>
              <Button size="small" type="link" @click="openLogs(record as OpsApi.CronJob)">
                日志
              </Button>
              <Button
                v-if="hasAccessByCodes(['ops:cron:delete'])"
                danger
                size="small"
                type="link"
                @click="confirmDelete(record as OpsApi.CronJob)"
              >
                删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <JobModal class="w-[520px]" title="计划任务">
      <JobForm />
    </JobModal>

    <!-- 执行日志 -->
    <Drawer
      :open="logOpen"
      :width="760"
      :title="`执行日志 - ${logJob?.name ?? ''}`"
      @close="logOpen = false"
    >
      <Table
        :data-source="logList"
        :loading="logLoading"
        :pagination="{
          current: logPagination.current,
          pageSize: logPagination.pageSize,
          showSizeChanger: false,
          showTotal: (total: number) => `共 ${total} 条`,
          total: logPagination.total,
          onChange: (page: number) => {
            logPagination.current = page;
            loadLogs();
          },
        }"
        :row-key="(record: OpsApi.CronLog) => record.id"
        size="small"
      >
        <Table.Column key="startedAt" title="开始时间" width="160">
          <template #default="{ record }">
            {{ formatTime(record.startedAt) }}
          </template>
        </Table.Column>
        <Table.Column key="exitCode" title="结果" width="80">
          <template #default="{ record }">
            <Tag :color="record.exitCode === 0 ? 'success' : 'error'">
              {{ record.exitCode === 0 ? '成功' : `失败(${record.exitCode})` }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="durationMs" title="耗时" width="90">
          <template #default="{ record }">
            {{ `${(record.durationMs / 1000).toFixed(1)}s` }}
          </template>
        </Table.Column>
        <Table.Column key="output" title="输出">
          <template #default="{ record }">
            <pre
              class="max-h-28 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs dark:bg-gray-800"
            >{{ record.output || '(无输出)' }}</pre>
          </template>
        </Table.Column>
      </Table>
    </Drawer>
  </Page>
</template>
