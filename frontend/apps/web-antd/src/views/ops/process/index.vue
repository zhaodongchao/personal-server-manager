<script lang="ts" setup>
import type { OpsApi } from '#/api';

import { onBeforeUnmount, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Button, Checkbox, Input, message, Modal, Space, Table, Tag } from 'ant-design-vue';

import { getProcessListApi, killProcessApi } from '#/api';

defineOptions({ name: 'OpsProcess' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const list = ref<OpsApi.ProcessInfo[]>([]);
const keyword = ref('');
const autoRefresh = ref(true);

let timer: null | ReturnType<typeof setInterval> = null;

async function load() {
  loading.value = true;
  try {
    list.value = await getProcessListApi(keyword.value || undefined);
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  load();
}

function confirmKill(row: OpsApi.ProcessInfo) {
  Modal.confirm({
    content: `确定强制终止进程 ${row.pid}（${row.cmd || row.user}）？`,
    onOk: async () => {
      await killProcessApi(row.pid);
      message.success('已发送终止信号');
      load();
    },
    title: '终止进程',
  });
}

function formatCpu(cpu: number) {
  return `${cpu.toFixed(1)}%`;
}

onMounted(() => {
  load();
  timer = setInterval(() => {
    if (autoRefresh.value) {
      load();
    }
  }, 5000);
});

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer);
  }
});
</script>

<template>
  <Page class="flex flex-col">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-64"
        placeholder="按 PID / 用户 / 命令过滤"
        @keyup.enter="onSearch"
        @press-enter="onSearch"
      />
      <Button type="primary" @click="onSearch">搜索</Button>
      <div class="ml-auto flex items-center gap-2">
        <Checkbox v-model:checked="autoRefresh">自动刷新</Checkbox>
        <Button @click="load">刷新</Button>
      </div>
    </div>

    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Table
        :data-source="list"
        :loading="loading"
        :pagination="false"
        :row-key="(record: OpsApi.ProcessInfo) => record.pid"
        :scroll="{ x: 900 }"
        size="small"
      >
        <Table.Column data-index="pid" title="PID" width="80" />
        <Table.Column data-index="user" title="用户" width="100" />
        <Table.Column key="cpu" title="CPU" width="90">
          <template #default="{ record }">
            <Tag :color="record.cpu > 80 ? 'error' : record.cpu > 30 ? 'warning' : 'default'">
              {{ formatCpu(record.cpu) }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="mem" title="内存" width="90">
          <template #default="{ record }">
            {{ formatCpu(record.mem) }}
          </template>
        </Table.Column>
        <Table.Column data-index="stat" title="状态" width="70" />
        <Table.Column data-index="elapsed" title="运行时长" width="100" />
        <Table.Column key="cmd" title="命令">
          <template #default="{ record }">
            <span class="break-all font-mono text-xs">{{ record.cmd }}</span>
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="90" fixed="right">
          <template #default="{ record }">
            <Space>
              <Button
                v-if="hasAccessByCodes(['ops:process:kill'])"
                danger
                size="small"
                type="link"
                @click="confirmKill(record as OpsApi.ProcessInfo)"
              >
                终止
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>
  </Page>
</template>
