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
  message,
  Modal,
  Radio,
  Space,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  createWebsiteApi,
  deleteWebsiteApi,
  getNginxStatusApi,
  getWebsitePageApi,
  toggleWebsiteApi,
  updateWebsiteApi,
} from '#/api';

defineOptions({ name: 'AppstackWebsite' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const nginxOk = ref(true);
const list = ref<AppstackApi.Website[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const keyword = ref('');

async function load() {
  loading.value = true;
  try {
    const res = await getWebsitePageApi({
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

async function loadNginxStatus() {
  try {
    nginxOk.value = await getNginxStatusApi();
  } catch {
    nginxOk.value = false;
  }
}

// ==================== 新建 / 编辑 ====================
const editing = ref(false);
const editingId = ref<null | string>(null);
const formRef = ref();
const saving = ref(false);

const form = reactive<AppstackApi.Website>({
  certPath: '',
  domain: '',
  keyPath: '',
  remark: '',
  siteName: '',
  siteType: 'proxy',
  sslEnabled: 0,
  staticRoot: '',
  upstream: '',
});

const modalOpen = ref(false);
function setFormState(v: Partial<AppstackApi.Website>) {
  Object.assign(
    form,
    {
      certPath: '',
      domain: '',
      keyPath: '',
      remark: '',
      siteName: '',
      siteType: 'proxy',
      sslEnabled: 0,
      staticRoot: '',
      upstream: '',
    },
    v,
  );
}

function openCreate() {
  editing.value = false;
  editingId.value = null;
  setFormState({});
  modalOpen.value = true;
}

function openEdit(record: AppstackApi.Website) {
  editing.value = true;
  editingId.value = record.id ?? null;
  setFormState({
    certPath: record.certPath ?? '',
    domain: record.domain,
    keyPath: record.keyPath ?? '',
    remark: record.remark ?? '',
    siteName: record.siteName,
    siteType: record.siteType,
    sslEnabled: record.sslEnabled ?? 0,
    staticRoot: record.staticRoot ?? '',
    upstream: record.upstream ?? '',
  });
  modalOpen.value = true;
}

async function save() {
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  const body: AppstackApi.Website = {
    certPath: form.sslEnabled === 1 ? form.certPath : undefined,
    domain: form.domain,
    id: editingId.value ?? undefined,
    keyPath: form.sslEnabled === 1 ? form.keyPath : undefined,
    remark: form.remark,
    siteName: form.siteName,
    siteType: form.siteType,
    sslEnabled: form.sslEnabled,
    staticRoot: form.siteType === 'static' ? form.staticRoot : undefined,
    upstream: form.siteType === 'proxy' ? form.upstream : undefined,
  };
  saving.value = true;
  try {
    if (editing.value && editingId.value) {
      await updateWebsiteApi(body);
      message.success('修改成功');
    } else {
      await createWebsiteApi(body);
      message.success('新增成功');
    }
    modalOpen.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

function confirmDelete(record: AppstackApi.Website) {
  Modal.confirm({
    content: `确定删除站点「${record.siteName}」（${record.domain}）？Nginx 配置将一并移除。`,
    onOk: async () => {
      await deleteWebsiteApi(record.id!);
      message.success('删除成功');
      await load();
    },
    title: '删除确认',
  });
}

function confirmToggle(record: AppstackApi.Website) {
  const next = record.status === 1 ? 0 : 1;
  Modal.confirm({
    content: next === 1
      ? `确定启用站点「${record.siteName}」？`
      : `确定停用站点「${record.siteName}」？站点将立即无法访问。`,
    onOk: async () => {
      await toggleWebsiteApi(record.id!, next);
      message.success(next === 1 ? '已启用' : '已停用');
      await load();
    },
    title: '确认',
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
  loadNginxStatus();
});
</script>

<template>
  <Page class="flex flex-col">
    <Alert
      v-if="!nginxOk"
      class="mb-3"
      message="未检测到可用的 Nginx，站点配置将无法写入并生效。请先安装并启动 nginx。"
      show-icon
      type="warning"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="w-64"
        placeholder="按域名/站点名称过滤"
        @keyup.enter="load"
        @press-enter="load"
      />
      <Button type="primary" @click="load">搜索</Button>
      <Button
        v-if="hasAccessByCodes(['appstack:website:add'])"
        class="ml-auto"
        type="primary"
        @click="openCreate"
      >
        新增站点
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
        :row-key="(record: AppstackApi.Website) => record.id ?? record.domain"
        :scroll="{ x: 900 }"
        size="small"
      >
        <Table.Column key="domain" title="域名" width="200">
          <template #default="{ record }">
            <span class="font-mono font-medium">{{ record.domain }}</span>
          </template>
        </Table.Column>
        <Table.Column data-index="siteName" title="站点名称" width="150" />
        <Table.Column key="siteType" title="类型" width="80">
          <template #default="{ record }">
            <Tag :color="record.siteType === 'proxy' ? 'blue' : 'green'">
              {{ record.siteType === 'proxy' ? '反向代理' : '静态站点' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="target" title="目标">
          <template #default="{ record }">
            <span class="break-all font-mono text-xs">
              {{ record.siteType === 'proxy' ? record.upstream : record.staticRoot }}
            </span>
          </template>
        </Table.Column>
        <Table.Column key="ssl" title="HTTPS" width="80">
          <template #default="{ record }">
            <Tag :color="record.sslEnabled === 1 ? 'success' : 'default'">
              {{ record.sslEnabled === 1 ? '开启' : '关闭' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="status" title="状态" width="80">
          <template #default="{ record }">
            <Tag :color="record.status === 1 ? 'success' : 'default'">
              {{ record.status === 1 ? '运行' : '停用' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="createdAt" title="创建时间" width="170">
          <template #default="{ record }">
            {{ formatTime(record.createdAt) }}
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="190" fixed="right">
          <template #default="{ record }">
            <Space :size="2" wrap>
              <Button
                v-if="hasAccessByCodes(['appstack:website:edit'])"
                size="small"
                type="link"
                @click="openEdit(record as AppstackApi.Website)"
              >
                编辑
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:website:edit'])"
                size="small"
                type="link"
                @click="confirmToggle(record as AppstackApi.Website)"
              >
                {{ record.status === 1 ? '停用' : '启用' }}
              </Button>
              <Button
                v-if="hasAccessByCodes(['appstack:website:delete'])"
                danger
                size="small"
                type="link"
                @click="confirmDelete(record as AppstackApi.Website)"
              >
                删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <Modal
      v-model:open="modalOpen"
      :confirm-loading="saving"
      :title="editing ? '编辑站点' : '新增站点'"
      @ok="save"
    >
      <Form
        ref="formRef"
        :model="form"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 18 }"
      >
        <Form.Item
          label="站点名称"
          name="siteName"
          :rules="[{ required: true, message: '请输入站点名称' }]"
        >
          <Input v-model:value="form.siteName" placeholder="如 我的博客" />
        </Form.Item>
        <Form.Item
          label="域名"
          name="domain"
          :rules="[{ required: true, message: '请输入域名' }]"
        >
          <Input v-model:value="form.domain" placeholder="如 www.example.com" />
        </Form.Item>
        <Form.Item label="站点类型" name="siteType">
          <Radio.Group v-model:value="form.siteType">
            <Radio.Button value="proxy">反向代理</Radio.Button>
            <Radio.Button value="static">静态站点</Radio.Button>
          </Radio.Group>
        </Form.Item>
        <Form.Item
          v-if="form.siteType === 'proxy'"
          label="反代目标"
          name="upstream"
          :rules="form.siteType === 'proxy' ? [{ required: true, message: '请输入反代目标' }] : []"
        >
          <Input v-model:value="form.upstream" placeholder="如 http://127.0.0.1:3000" />
        </Form.Item>
        <Form.Item
          v-else
          label="站点根目录"
          name="staticRoot"
          :rules="form.siteType === 'static' ? [{ required: true, message: '请输入站点根目录' }] : []"
        >
          <Input
            v-model:value="form.staticRoot"
            placeholder="如 /www/wwwroot/example（须在文件白名单内）"
          />
        </Form.Item>
        <Form.Item label="HTTPS" name="sslEnabled">
          <Switch v-model:checked="form.sslEnabled" :checked-value="1" :un-checked-value="0" />
        </Form.Item>
        <template v-if="form.sslEnabled === 1">
          <Form.Item label="证书路径" name="certPath">
            <Input v-model:value="form.certPath" placeholder="如 /etc/nginx/ssl/example.pem" />
          </Form.Item>
          <Form.Item label="私钥路径" name="keyPath">
            <Input v-model:value="form.keyPath" placeholder="如 /etc/nginx/ssl/example.key" />
          </Form.Item>
        </template>
        <Form.Item label="备注" name="remark">
          <Input v-model:value="form.remark" />
        </Form.Item>
      </Form>
    </Modal>
  </Page>
</template>
