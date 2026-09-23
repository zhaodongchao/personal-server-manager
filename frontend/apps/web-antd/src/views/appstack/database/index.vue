<script lang="ts" setup>
import type { AppstackApi } from '#/api';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  backupDatabaseApi,
  createDatabaseApi,
  createIdSourceApi,
  deleteDatabaseApi,
  deleteIdSourceApi,
  getDatabaseCharsetsApi,
  getDatabasePageApi,
  getIdSourcePageApi,
  getIdSourceStatusApi,
  initIdSourceApi,
  probeIdSourceApi,
  restoreDatabaseApi,
  updateIdSourceApi,
} from '#/api';

defineOptions({ name: 'AppstackDatabase' });

const { hasAccessByCodes } = useAccess();

// ==================== 页签 ====================
// 用 Segmented + v-if 而不是 Tabs：本页的表格区依赖「Page 是 flex 列容器、
// 表格区 flex-1 自适应高度」这条链，Tabs 会在中间插一层高度不确定的容器把链打断。
const tabOptions = [
  { label: 'MySQL 实例', value: 'db' },
  { label: '取号数据源', value: 'idsource' },
];
const activeTab = ref('db');

// ==================== MySQL 实例 ====================
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

// ==================== 取号数据源 ====================
// ID 生成器的「自增计数器 / 序列」两类方案真连库取号时，连的就是这里登记的目标库。
const idLoading = ref(false);
const idList = ref<AppstackApi.IdSource[]>([]);
const idPagination = reactive({ current: 1, pageSize: 10, total: 0 });
const idKeyword = ref('');
const idStatus = ref<AppstackApi.IdSourceStatus>({
  allowedDatabases: [],
  autoPrefix: 'psm_',
  cipherReady: true,
  defaultSequence: 'psm_id_seq',
  defaultTable: 'psm_id_demo',
});
const idBusyId = ref('');

async function loadIdStatus() {
  try {
    idStatus.value = await getIdSourceStatusApi();
  } catch {
    // 状态拿不到不影响列表，页面仍可查看已有数据源
  }
}

async function loadIdSources() {
  idLoading.value = true;
  try {
    const res = await getIdSourcePageApi({
      keyword: idKeyword.value || undefined,
      pageNum: idPagination.current,
      pageSize: idPagination.pageSize,
    });
    idList.value = res.records ?? [];
    idPagination.total = res.total ?? 0;
  } finally {
    idLoading.value = false;
  }
}

function onTabChange(value: any) {
  if (value === 'idsource') {
    loadIdStatus();
    loadIdSources();
  }
}

/** 取号对象名（后端已回落到默认值） */
function targetOf(record: AppstackApi.IdSource) {
  if (record.dbType === 'MYSQL') {
    return record.tableName || idStatus.value.defaultTable;
  }
  return record.sequenceName || idStatus.value.defaultSequence;
}

// ---- 新增 / 编辑 ----
const idModalOpen = ref(false);
const idSaving = ref(false);
const idFormRef = ref();
const idForm = reactive({
  autoInit: true,
  dbName: 'psm_tools',
  dbType: 'POSTGRESQL',
  host: '127.0.0.1',
  id: '',
  name: '',
  password: '',
  port: 5432,
  remark: '',
  sequenceName: '',
  status: true,
  tableName: '',
  username: '',
});

function onDbTypeChange(value: any) {
  // 端口跟着类型走，避免默认值把 5432 留给 MySQL
  if (value === 'MYSQL') {
    if (idForm.port === 5432) {
      idForm.port = 3306;
    }
  } else if (idForm.port === 3306) {
    idForm.port = 5432;
  }
}

function openIdSource(record?: AppstackApi.IdSource) {
  if (record) {
    idForm.id = record.id ?? '';
    idForm.name = record.name;
    idForm.dbType = record.dbType;
    idForm.host = record.host;
    idForm.port = record.port;
    idForm.dbName = record.dbName;
    idForm.username = record.username;
    idForm.password = '';
    idForm.tableName = record.tableName ?? '';
    idForm.sequenceName = record.sequenceName ?? '';
    idForm.autoInit = record.autoInit ?? true;
    idForm.status = record.status;
    idForm.remark = record.remark ?? '';
  } else {
    idForm.id = '';
    idForm.name = '';
    idForm.dbType = 'POSTGRESQL';
    idForm.host = '127.0.0.1';
    idForm.port = 5432;
    idForm.dbName = 'psm_tools';
    idForm.username = '';
    idForm.password = '';
    idForm.tableName = '';
    idForm.sequenceName = '';
    idForm.autoInit = true;
    idForm.status = true;
    idForm.remark = '';
  }
  idModalOpen.value = true;
}

async function doSaveIdSource() {
  try {
    await idFormRef.value?.validate();
  } catch {
    return;
  }
  idSaving.value = true;
  try {
    const body: Partial<AppstackApi.IdSource> = {
      autoInit: idForm.autoInit,
      dbName: idForm.dbName.trim(),
      dbType: idForm.dbType,
      host: idForm.host.trim(),
      name: idForm.name.trim(),
      // 留空表示不修改口令：页面从不回显明文，若把空值当成「清空」，
      // 改一次备注就会把口令抹掉
      password: idForm.password ? idForm.password : undefined,
      port: idForm.port,
      remark: idForm.remark || undefined,
      sequenceName: idForm.sequenceName.trim() || undefined,
      status: idForm.status,
      tableName: idForm.tableName.trim() || undefined,
      username: idForm.username.trim(),
    };
    if (idForm.id) {
      await updateIdSourceApi(idForm.id, body);
      message.success('已保存');
    } else {
      await createIdSourceApi(body);
      message.success('已新增');
    }
    idModalOpen.value = false;
    await loadIdSources();
  } finally {
    idSaving.value = false;
  }
}

function confirmDeleteIdSource(record: AppstackApi.IdSource) {
  Modal.confirm({
    content: `确定删除取号数据源「${record.name}」？只删除面板里的登记记录，不会触碰目标库中的任何对象。`,
    onOk: async () => {
      await deleteIdSourceApi(record.id!);
      message.success('删除成功');
      await loadIdSources();
    },
    title: '删除确认',
  });
}

// ---- 测试连接 / 初始化 ----
const probeOpen = ref(false);
const probeResult = ref<null | AppstackApi.IdSourceProbe>(null);
const probeTitle = ref('');

async function doProbe(record: AppstackApi.IdSource) {
  idBusyId.value = record.id ?? '';
  try {
    probeResult.value = await probeIdSourceApi(record.id!);
    probeTitle.value = `连接测试 · ${record.name}`;
    probeOpen.value = true;
  } finally {
    idBusyId.value = '';
  }
}

const initOpen = ref(false);
const initResult = ref<null | AppstackApi.IdSourceInit>(null);

async function doInit(record: AppstackApi.IdSource) {
  idBusyId.value = record.id ?? '';
  try {
    initResult.value = await initIdSourceApi(record.id!);
    initOpen.value = true;
    await loadIdSources();
  } finally {
    idBusyId.value = '';
  }
}

// ==================== 初始化 ====================
onMounted(() => {
  load();
  loadCharsets();
  loadIdStatus();
});
</script>

<template>
  <Page class="flex flex-col">
    <div class="mb-3">
      <Segmented v-model:value="activeTab" :options="tabOptions" @change="onTabChange" />
    </div>

    <!-- ==================== MySQL 实例 ==================== -->
    <template v-if="activeTab === 'db'">
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
    </template>

    <!-- ==================== 取号数据源 ==================== -->
    <template v-else>
      <Alert
        v-if="!idStatus.cipherReady"
        class="mb-3"
        description="口令需要加密后才能落库，而加密密钥既未配置、也无法从宿主代理密钥派生。请为后端配置 serverpanel.secret.key（环境变量 PANEL_SECRET_KEY）后重启；在此之前可以查看已有数据源，但无法新增或修改。"
        message="口令加密密钥不可用 —— 暂时无法保存数据源"
        show-icon
        type="warning"
      />
      <Alert
        v-else
        class="mb-3"
        show-icon
        type="info"
      >
        <template #message>
          ID 生成器的「自增计数器 / 序列」两类方案会真连到这里登记的目标库取号
        </template>
        <template #description>
          <div>
            允许的库：<span class="font-mono">{{ idStatus.allowedDatabases.join('、') || '（未配置）' }}</span>；
            自动创建的对象必须带 <span class="font-mono">{{ idStatus.autoPrefix }}</span> 前缀。
            面板自身库与 Dify 生产库（server_panel / dify / dify_plugin / postgres）是硬黑名单，配置也放不开。
          </div>
        </template>
      </Alert>

      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Input
          v-model:value="idKeyword"
          allow-clear
          class="w-64"
          placeholder="按名称 / 库名 / 主机过滤"
          @press-enter="loadIdSources"
        />
        <Button type="primary" @click="loadIdSources">搜索</Button>
        <Button
          v-if="hasAccessByCodes(['appstack:id-source:save'])"
          :disabled="!idStatus.cipherReady"
          class="ml-auto"
          type="primary"
          @click="openIdSource()"
        >
          新增数据源
        </Button>
      </div>

      <div class="min-h-0 flex-1 overflow-auto rounded border">
        <Table
          :data-source="idList"
          :loading="idLoading"
          :pagination="{
            current: idPagination.current,
            pageSize: idPagination.pageSize,
            showSizeChanger: true,
            showTotal: (total: number) => `共 ${total} 条`,
            total: idPagination.total,
            onChange: (page: number, size: number) => {
              idPagination.current = page;
              idPagination.pageSize = size;
              loadIdSources();
            },
          }"
          :row-key="(record: AppstackApi.IdSource) => record.id ?? record.name"
          :scroll="{ x: 1180 }"
          size="small"
        >
          <Table.Column key="name" title="名称" width="150" />
          <Table.Column key="dbType" title="类型" width="110">
            <template #default="{ record }">
              <Tag :color="record.dbType === 'MYSQL' ? 'orange' : 'blue'">
                {{ record.dbTypeLabel ?? record.dbType }}
              </Tag>
            </template>
          </Table.Column>
          <Table.Column key="addr" title="地址" width="180">
            <template #default="{ record }">
              <span class="font-mono text-xs">{{ record.host }}:{{ record.port }}</span>
            </template>
          </Table.Column>
          <Table.Column key="dbName" title="库名" width="140">
            <template #default="{ record }">
              <span class="font-mono">{{ record.dbName }}</span>
            </template>
          </Table.Column>
          <Table.Column key="username" title="账号" width="120">
            <template #default="{ record }">
              <span class="font-mono text-xs">{{ record.username }}</span>
            </template>
          </Table.Column>
          <Table.Column key="password" title="口令" width="90">
            <template #default="{ record }">
              <span class="font-mono text-xs text-gray-400">
                {{ record.passwordMasked ?? '******' }}
              </span>
            </template>
          </Table.Column>
          <Table.Column key="target" title="取号对象" width="150">
            <template #default="{ record }">
              <span class="font-mono text-xs">{{ targetOf(record as AppstackApi.IdSource) }}</span>
            </template>
          </Table.Column>
          <Table.Column key="status" title="状态" width="80">
            <template #default="{ record }">
              <Tag :color="record.status ? 'green' : 'default'">
                {{ record.status ? '启用' : '停用' }}
              </Tag>
            </template>
          </Table.Column>
          <Table.Column data-index="remark" title="备注" />
          <Table.Column key="action" fixed="right" title="操作" width="250">
            <template #default="{ record }">
              <Space :size="2" wrap>
                <Button
                  v-if="hasAccessByCodes(['appstack:id-source:probe'])"
                  :loading="idBusyId === record.id"
                  size="small"
                  type="link"
                  @click="doProbe(record as AppstackApi.IdSource)"
                >
                  测试
                </Button>
                <Button
                  v-if="hasAccessByCodes(['appstack:id-source:probe'])"
                  :loading="idBusyId === record.id"
                  size="small"
                  type="link"
                  @click="doInit(record as AppstackApi.IdSource)"
                >
                  初始化
                </Button>
                <Button
                  v-if="hasAccessByCodes(['appstack:id-source:save'])"
                  size="small"
                  type="link"
                  @click="openIdSource(record as AppstackApi.IdSource)"
                >
                  编辑
                </Button>
                <Button
                  v-if="hasAccessByCodes(['appstack:id-source:delete'])"
                  danger
                  size="small"
                  type="link"
                  @click="confirmDeleteIdSource(record as AppstackApi.IdSource)"
                >
                  删除
                </Button>
              </Space>
            </template>
          </Table.Column>
        </Table>
      </div>
    </template>

    <!-- 建库 -->
    <Modal
      v-model:open="createOpen"
      :confirm-loading="createSaving"
      title="创建数据库"
      @ok="doCreate"
    >
      <Form
        ref="createFormRef"
        :label-col="{ span: 6 }"
        :model="createForm"
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
          <Select
            v-model:value="createForm.charset"
            :options="charsets.map((c) => ({ label: c, value: c }))"
          />
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

    <!-- 取号数据源：新增 / 编辑 -->
    <Modal
      v-model:open="idModalOpen"
      :confirm-loading="idSaving"
      :title="idForm.id ? '编辑取号数据源' : '新增取号数据源'"
      :width="600"
      @ok="doSaveIdSource"
    >
      <Form
        ref="idFormRef"
        :label-col="{ span: 6 }"
        :model="idForm"
        :wrapper-col="{ span: 18 }"
      >
        <Form.Item
          label="名称"
          name="name"
          :rules="[{ required: true, message: '请输入名称' }]"
        >
          <Input v-model:value="idForm.name" placeholder="如 本地 PostgreSQL 15" />
        </Form.Item>
        <Form.Item
          label="类型"
          name="dbType"
          :rules="[{ required: true, message: '请选择类型' }]"
        >
          <Select
            v-model:value="idForm.dbType"
            :options="[
              { label: 'PostgreSQL（用序列方案）', value: 'POSTGRESQL' },
              { label: 'MySQL（用自增计数器方案）', value: 'MYSQL' },
            ]"
            @change="onDbTypeChange"
          />
        </Form.Item>
        <Form.Item
          label="主机"
          name="host"
          :rules="[{ required: true, message: '请输入主机' }]"
        >
          <Input v-model:value="idForm.host" placeholder="127.0.0.1" />
        </Form.Item>
        <Form.Item
          label="端口"
          name="port"
          :rules="[{ required: true, message: '请输入端口' }]"
        >
          <InputNumber v-model:value="idForm.port" :max="65535" :min="1" class="w-full" />
        </Form.Item>
        <Form.Item
          label="库名"
          name="dbName"
          :rules="[{ required: true, message: '请输入库名' }]"
        >
          <Input v-model:value="idForm.dbName" placeholder="psm_tools（专用库）" />
        </Form.Item>
        <Form.Item
          label="账号"
          name="username"
          :rules="[{ required: true, message: '请输入账号' }]"
        >
          <Input v-model:value="idForm.username" />
        </Form.Item>
        <Form.Item
          :rules="idForm.id ? [] : [{ required: true, message: '请输入口令' }]"
          :extra="idForm.id ? '留空表示不修改口令' : '口令以 AES-256-GCM 加密后落库，永不回显'"
          label="口令"
          name="password"
        >
          <Input.Password
            v-model:value="idForm.password"
            :placeholder="idForm.id ? '留空则不修改' : '请输入数据库口令'"
          />
        </Form.Item>
        <Form.Item
          v-if="idForm.dbType === 'MYSQL'"
          :extra="`留空则用默认表 ${idStatus.defaultTable}；自动创建的表必须带 ${idStatus.autoPrefix} 前缀`"
          label="自增表名"
          name="tableName"
        >
          <Input v-model:value="idForm.tableName" placeholder="psm_id_demo" />
        </Form.Item>
        <Form.Item
          v-else
          :extra="`留空则用默认序列 ${idStatus.defaultSequence}；自动创建的序列必须带 ${idStatus.autoPrefix} 前缀`"
          label="序列名"
          name="sequenceName"
        >
          <Input v-model:value="idForm.sequenceName" placeholder="psm_id_seq" />
        </Form.Item>
        <Form.Item
          :extra="'开启后，取号前若对象不存在会自动创建（IF NOT EXISTS 语义）'"
          label="自动初始化"
          name="autoInit"
        >
          <Switch v-model:checked="idForm.autoInit" />
        </Form.Item>
        <Form.Item label="启用" name="status">
          <Switch v-model:checked="idForm.status" />
        </Form.Item>
        <Form.Item label="备注" name="remark">
          <Input v-model:value="idForm.remark" />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 连接测试结果 -->
    <Modal v-model:open="probeOpen" :footer="null" :title="probeTitle" :width="560">
      <Alert
        :description="probeResult?.message"
        :message="probeResult?.ok ? '连接成功' : '连接失败'"
        show-icon
        :type="probeResult?.ok ? 'success' : 'error'"
      />
      <div v-if="probeResult?.serverVersion" class="mt-3 rounded border p-3 font-mono text-xs">
        {{ probeResult.serverVersion }}
      </div>
      <Button class="mt-3 w-full" type="primary" @click="probeOpen = false">关闭</Button>
    </Modal>

    <!-- 初始化结果 -->
    <Modal v-model:open="initOpen" :footer="null" title="初始化取号对象" :width="560">
      <Alert :message="initResult?.message" show-icon type="success" />
      <div class="mt-3 space-y-1 rounded border p-3 font-mono text-xs">
        <div>取号对象：{{ initResult?.target }}</div>
        <div v-if="initResult?.serverVersion">数据库：{{ initResult.serverVersion }}</div>
      </div>
      <Button class="mt-3 w-full" type="primary" @click="initOpen = false">关闭</Button>
    </Modal>
  </Page>
</template>
