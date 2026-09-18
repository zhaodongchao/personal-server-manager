<script lang="ts" setup>
import type { AppstackApi } from '#/api';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Button,
  Form,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  backupDatabaseApi,
  createDatabaseApi,
  deleteDatabaseApi,
  getDatabaseCharsetsApi,
  getDatabasePageApi,
  restoreDatabaseApi,
} from '#/api';

defineOptions({ name: 'AppstackDatabase' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const list = ref<AppstackApi.Database[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const keyword = ref('');
const charsets = ref<string[]>(['utf8mb4', 'utf8', 'latin1', 'gbk']);

async function load() {
  loading.value = true;
  try {
    const res = await getDatabasePageApi({
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

async function loadCharsets() {
  try {
    charsets.value = await getDatabaseCharsetsApi();
  } catch {
    // 使用默认字符集列表
  }
}

// ==================== 建库 ====================
const createOpen = ref(false);
const createSaving = ref(false);
const createForm = reactive({ charset: 'utf8mb4', dbName: '', remark: '' });
const createFormRef = ref();

const createdResult = ref<null | AppstackApi.DatabaseCreateResult>(null);
const resultOpen = ref(false);

async function doCreate() {
  try {
    await createFormRef.value?.validate();
  } catch {
    return;
  }
  createSaving.value = true;
  try {
    createdResult.value = await createDatabaseApi({
      charset: createForm.charset,
      dbName: createForm.dbName,
      remark: createForm.remark || undefined,
    });
    createOpen.value = false;
    createForm.dbName = '';
    createForm.remark = '';
    resultOpen.value = true;
    await load();
  } finally {
    createSaving.value = false;
  }
}

// ==================== 删库 ====================
function confirmDelete(record: AppstackApi.Database) {
  Modal.confirm({
    content: `确定删除数据库「${record.dbName}」？库、授权账号及其数据将被一并删除，且不可恢复。`,
    onOk: async () => {
      await deleteDatabaseApi(record.id!);
      message.success('删除成功');
      await load();
    },
    title: '删除确认',
  });
}

// ==================== 备份 / 恢复 ====================
const backupBusy = ref(false);

async function doBackup(record: AppstackApi.Database) {
  backupBusy.value = true;
  try {
    const file = await backupDatabaseApi(record.id!);
    Modal.success({
      content: `备份完成：${file}`,
      title: '备份成功',
    });
  } finally {
    backupBusy.value = false;
  }
}

const restoreOpen = ref(false);
const restoreId = ref<null | string>(null);
const restoreName = ref('');
const restoreBusy = ref(false);

function openRestore(record: AppstackApi.Database) {
  restoreId.value = record.id ?? null;
  restoreName.value = '';
  restoreOpen.value = true;
}

async function doRestore() {
  const file = restoreName.value.trim();
  if (!file) {
    message.warning('请输入备份文件名');
    return;
  }
  Modal.confirm({
    content: `恢复会覆盖「${restoreName.value}」的现有数据，确定继续？`,
    onOk: async () => {
      restoreBusy.value = true;
      try {
        await restoreDatabaseApi(restoreId.value!, file);
        restoreOpen.value = false;
        message.success('恢复完成');
      } finally {
        restoreBusy.value = false;
      }
    },
    title: '恢复确认',
  });
}

function formatTime(s?: string) {
  if (!s) {
    return '-';
  }
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

onMounted(() => {
  load();
  loadCharsets();
});
</script>

<template>
  <Page class="flex flex-col">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-64"
        placeholder="按库名过滤"
        @keyup.enter="load"
        @press-enter="load"
      />
      <Button type="primary" @click="load">搜索</Button>
      <Button
        v-if="hasAccessByCodes(['appstack:database:add'])"
        class="ml-auto"
        type="primary"
        @click="createOpen = true"
      >
        创建数据库
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
        :row-key="(record: AppstackApi.Database) => record.id ?? record.dbName"
        :scroll="{ x: 780 }"
        size="small"
      >
        <Table.Column key="dbName" title="库名" width="200">
          <template #default="{ record }">
            <span class="font-mono font-medium">{{ record.dbName }}</span>
          </template>
        </Table.Column>
        <Table.Column key="dbUser" title="授权账号" width="160">
          <template #default="{ record }">
            <span class="font-mono text-xs">{{ record.dbUser }}</span>
          </template>
        </Table.Column>
        <Table.Column key="charset" title="字符集" width="100">
          <template #default="{ record }">
            <Tag color="blue">{{ record.charset }}</Tag>
          </template>
        </Table.Column>
        <Table.Column data-index="remark" title="备注" />
        <Table.Column key="createdAt" title="创建时间" width="170">
          <template #default="{ record }">
            {{ formatTime(record.createdAt) }}
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="200" fixed="right">
          <template #default="{ record }">
            <Space :size="2" wrap>
              <Button
                v-if="hasAccessByCodes(['appstack:database:backup'])"
                size="small"
                type="link"
                @click="doBackup(record as AppstackApi.Database)"
              >
                备份
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:database:backup'])"
                size="small"
                type="link"
                @click="openRestore(record as AppstackApi.Database)"
              >
                恢复
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:database:delete'])"
                danger
                size="small"
                type="link"
                @click="confirmDelete(record as AppstackApi.Database)"
              >
                删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <!-- 建库 -->
    <Modal
      v-model:open="createOpen"
      :confirm-loading="createSaving"
      title="创建数据库"
      @ok="doCreate"
    >
      <Form
        ref="createFormRef"
        :model="createForm"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 18 }"
      >
        <Form.Item
          label="库名"
          name="dbName"
          :rules="[
            { required: true, message: '请输入库名' },
            { pattern: /^[a-zA-Z0-9_]+$/, message: '仅支持字母、数字、下划线' },
          ]"
        >
          <Input v-model:value="createForm.dbName" placeholder="如 myblog（同时作为账号名）" />
        </Form.Item>
        <Form.Item label="字符集" name="charset">
          <Select v-model:value="createForm.charset" :options="charsets.map((c) => ({ label: c, value: c }))" />
        </Form.Item>
        <Form.Item label="备注" name="remark">
          <Input v-model:value="createForm.remark" />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 建库结果：账号密码仅此一次展示 -->
    <Modal v-model:open="resultOpen" :footer="null" title="创建成功">
      <p class="mb-2 text-sm text-gray-500">
        账号密码仅在本次创建时展示，请立即保存，面板不会再次提供。
      </p>
      <div class="space-y-2 rounded border p-3 font-mono text-sm">
        <div>库名：{{ createdResult?.dbName }}</div>
        <div>账号：{{ createdResult?.username }}</div>
        <div>密码：{{ createdResult?.password }}</div>
        <div>字符集：{{ createdResult?.charset }}</div>
      </div>
      <Button class="mt-3 w-full" type="primary" @click="resultOpen = false">我已保存</Button>
    </Modal>

    <!-- 恢复 -->
    <Modal
      v-model:open="restoreOpen"
      :confirm-loading="restoreBusy"
      title="从备份恢复"
      @ok="doRestore"
    >
      <Input
        v-model:value="restoreName"
        placeholder="备份文件名，如 myblog_20260918120000.sql"
      />
    </Modal>
  </Page>
</template>
