<script lang="ts" setup>
import type { OpsApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Button,
  Drawer,
  Input,
  message,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import { getServiceDetailApi, getServiceListApi, serviceActionApi } from '#/api';

defineOptions({ name: 'OpsService' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const list = ref<OpsApi.ServiceInfo[]>([]);
const keyword = ref('');
const acting = ref('');

async function load() {
  loading.value = true;
  try {
    list.value = await getServiceListApi(keyword.value || undefined);
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  load();
}

const ACTIONS = [
  { key: 'start', label: '启动', color: 'green' },
  { key: 'stop', label: '停止', color: 'red' },
  { key: 'restart', label: '重启', color: 'orange' },
  { key: 'reload', label: '重载', color: 'blue' },
  { key: 'enable', label: '开机自启', color: 'purple' },
  { key: 'disable', label: '取消自启', color: 'default' },
] as const;

async function doAction(row: OpsApi.ServiceInfo, action: string) {
  acting.value = `${row.name}:${action}`;
  try {
    await serviceActionApi(row.name, action);
    message.success(`「${row.name}」${action} 执行成功`);
    await load();
  } finally {
    acting.value = '';
  }
}

// ==================== 详情 ====================
const detailOpen = ref(false);
const detailLoading = ref(false);
const detailName = ref('');
const detailContent = ref('');

async function openDetail(row: OpsApi.ServiceInfo) {
  detailName.value = row.name;
  detailContent.value = '';
  detailOpen.value = true;
  detailLoading.value = true;
  try {
    detailContent.value = await getServiceDetailApi(row.name);
  } finally {
    detailLoading.value = false;
  }
}

function activeColor(active: string) {
  if (active === 'active') {
    return 'success';
  }
  if (active === 'failed') {
    return 'error';
  }
  return 'default';
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
        placeholder="按服务名过滤"
        @keyup.enter="onSearch"
        @press-enter="onSearch"
      />
      <Button type="primary" @click="onSearch">搜索</Button>
      <Button class="ml-auto" @click="load">刷新</Button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Table
        :data-source="list"
        :loading="loading"
        :pagination="false"
        :row-key="(record: OpsApi.ServiceInfo) => record.name"
        :scroll="{ x: 760 }"
        size="small"
      >
        <Table.Column key="name" title="服务" width="200">
          <template #default="{ record }">
            <a class="font-medium" @click="openDetail(record as OpsApi.ServiceInfo)">
              {{ record.name }}
            </a>
          </template>
        </Table.Column>
        <Table.Column key="active" title="运行状态" width="100">
          <template #default="{ record }">
            <Tag :color="activeColor(record.active)">{{ record.active }}</Tag>
          </template>
        </Table.Column>
        <Table.Column key="sub" title="子状态" width="90">
          <template #default="{ record }">
            {{ record.sub }}
          </template>
        </Table.Column>
        <Table.Column key="enabled" title="自启" width="90">
          <template #default="{ record }">
            <Tag v-if="record.enabled === 'enabled'" color="blue">已启用</Tag>
            <Tag v-else-if="record.enabled === 'disabled'" color="default">已禁用</Tag>
            <span v-else>{{ record.enabled }}</span>
          </template>
        </Table.Column>
        <Table.Column key="desc" title="描述">
          <template #default="{ record }">
            <span class="text-xs text-gray-500">{{ record.description }}</span>
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="300" fixed="right">
          <template #default="{ record }">
            <Space :size="2" wrap>
              <template v-for="action in ACTIONS" :key="action.key">
                <Popconfirm
                  :title="`确定对 ${record.name} 执行 ${action.label}？`"
                  @confirm="doAction(record as OpsApi.ServiceInfo, action.key)"
                >
                  <Button
                    v-if="hasAccessByCodes(['ops:service:manage'])"
                    :loading="acting === `${record.name}:${action.key}`"
                    size="small"
                    :type="action.key === 'restart' ? 'primary' : 'default'"
                  >
                    {{ action.label }}
                  </Button>
                </Popconfirm>
              </template>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <!-- 服务详情 -->
    <Drawer
      :open="detailOpen"
      :width="640"
      :title="detailName"
      @close="detailOpen = false"
    >
      <Spin :spinning="detailLoading">
        <pre class="max-h-[70vh] overflow-auto rounded bg-gray-50 p-3 font-mono text-xs dark:bg-gray-800">{{ detailContent }}</pre>
      </Spin>
    </Drawer>
  </Page>
</template>
