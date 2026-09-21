<script lang="ts" setup>
/**
 * 四层转发（stream TCP/UDP）管理抽屉。
 *
 * <p>自包含：加载当前实例的 stream 列表，支持新建/编辑/启停/删除。写入
 * stream include 目录，`listen port` 冲突由后端拦截（6015 NGINX_STREAM_PORT_CONFLICT）。
 * 删除需键入转发名二次确认。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { NginxApi } from '#/api';

import { ref, watch } from 'vue';

import {
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import {
  deleteNginxStreamApi,
  getNginxStreamPageApi,
  saveNginxStreamApi,
  toggleNginxStreamApi,
  updateNginxStreamApi,
} from '#/api';

import { STREAM_PROTOCOL_OPTIONS, streamProtocolColor, streamProtocolLabel } from '../utils';

import DangerConfirmModal from './DangerConfirmModal.vue';

defineOptions({ name: 'OpsNginxStreamDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
  canWrite?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  changed: [];
}>();

interface StreamForm {
  id?: string;
  name: string;
  protocol: string;
  listenPort?: number;
  upstreamHost: string;
  upstreamPort?: number;
  proxyTimeout: number;
  remark: string;
}

function blank(): StreamForm {
  return {
    name: '',
    protocol: 'tcp',
    listenPort: undefined,
    upstreamHost: '',
    upstreamPort: undefined,
    proxyTimeout: 600,
    remark: '',
  };
}

const loading = ref(false);
const rows = ref<NginxApi.NginxStream[]>([]);
const editing = ref(false);
const form = ref<StreamForm>(blank());
const saving = ref(false);

const dangerOpen = ref(false);
const dangerTarget = ref<NginxApi.NginxStream | null>(null);

async function load() {
  loading.value = true;
  try {
    const res = await getNginxStreamPageApi({
      instanceId: props.instanceId,
      pageNum: 1,
      pageSize: 100,
    });
    rows.value = res?.records ?? [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    editing.value = false;
    form.value = blank();
    void load();
  },
);

function createNew() {
  form.value = blank();
  editing.value = true;
}

function edit(s: NginxApi.NginxStream) {
  form.value = {
    id: s.id,
    name: s.name,
    protocol: s.protocol ?? 'tcp',
    listenPort: s.listenPort,
    upstreamHost: s.upstreamHost ?? '',
    upstreamPort: s.upstreamPort,
    proxyTimeout: s.proxyTimeout ?? 600,
    remark: s.remark ?? '',
  };
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
  form.value = blank();
}

async function save() {
  if (!form.value.name.trim()) {
    message.warning('请填写转发名');
    return;
  }
  if (!form.value.listenPort || !form.value.upstreamHost.trim() || !form.value.upstreamPort) {
    message.warning('请完整填写监听端口与目标地址');
    return;
  }
  const body: NginxApi.NginxStreamBody = {
    id: form.value.id,
    instanceId: props.instanceId,
    name: form.value.name.trim(),
    protocol: form.value.protocol,
    listenPort: form.value.listenPort,
    upstreamHost: form.value.upstreamHost.trim(),
    upstreamPort: form.value.upstreamPort,
    proxyTimeout: form.value.proxyTimeout,
    remark: form.value.remark,
  };
  saving.value = true;
  try {
    if (form.value.id) {
      await updateNginxStreamApi(body);
      message.success('转发已更新');
    } else {
      await saveNginxStreamApi(body);
      message.success('转发已创建');
    }
    editing.value = false;
    form.value = blank();
    await load();
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function toggle(s: NginxApi.NginxStream) {
  const next = s.status === 1 ? 0 : 1;
  await toggleNginxStreamApi(s.id as string, next);
  message.success(next === 1 ? '转发已启用' : '转发已停用');
  await load();
  emit('changed');
}

function askDelete(s: NginxApi.NginxStream) {
  dangerTarget.value = s;
  dangerOpen.value = true;
}

async function doDelete(keyword: string) {
  const s = dangerTarget.value;
  if (!s) return;
  await deleteNginxStreamApi(s.id as string, keyword);
  message.success(`转发「${s.name}」已删除`);
  await load();
  emit('changed');
}

const columns = [
  { dataIndex: 'name', title: '名称', width: 150 },
  { dataIndex: 'protocol', title: '协议', width: 76 },
  { dataIndex: 'listen', title: '监听', width: 110 },
  { dataIndex: 'target', title: '目标', width: 180 },
  { dataIndex: 'status', title: '状态', width: 80 },
  { dataIndex: 'actions', title: '操作', width: 170 },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="700"
    title="四层转发（stream）"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <template v-if="!editing">
        <div class="mb-3 flex items-center justify-between">
          <span class="text-sm text-gray-500">共 {{ rows.length }} 条转发</span>
          <Button v-if="canWrite" size="small" type="primary" @click="createNew">
            新建转发
          </Button>
        </div>
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          :row-key="(r: NginxApi.NginxStream) => r.id as string"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'name'">
              <span class="font-medium">{{ (record as NginxApi.NginxStream).name }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'protocol'">
              <Tag :color="streamProtocolColor((record as NginxApi.NginxStream).protocol)">
                {{ streamProtocolLabel((record as NginxApi.NginxStream).protocol) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'listen'">
              <span class="font-mono text-xs">
                {{ (record as NginxApi.NginxStream).listenPort }}
              </span>
            </template>
            <template v-else-if="column.dataIndex === 'target'">
              <span class="font-mono text-xs">
                {{ (record as NginxApi.NginxStream).upstreamHost }}:{{ (record as NginxApi.NginxStream).upstreamPort }}
              </span>
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <Tag :color="(record as NginxApi.NginxStream).status === 1 ? 'green' : 'default'">
                {{ (record as NginxApi.NginxStream).status === 1 ? '启用' : '停用' }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'actions'">
              <Space v-if="canWrite" :size="0">
                <Button size="small" type="link" @click="edit(record as NginxApi.NginxStream)">
                  编辑
                </Button>
                <Button size="small" type="link" @click="toggle(record as NginxApi.NginxStream)">
                  {{ (record as NginxApi.NginxStream).status === 1 ? '停用' : '启用' }}
                </Button>
                <Button
                  danger
                  size="small"
                  type="link"
                  @click="askDelete(record as NginxApi.NginxStream)"
                >
                  删除
                </Button>
              </Space>
            </template>
          </template>
        </Table>
        <Empty
          v-if="rows.length === 0"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          description="暂无四层转发"
        />
      </template>

      <template v-else>
        <Form layout="vertical">
          <div class="flex items-start gap-2">
            <Form.Item class="flex-1" label="转发名" required>
              <Input v-model:value="form.name" placeholder="如 mysql-proxy" />
            </Form.Item>
            <Form.Item class="w-32" label="协议">
              <Select v-model:value="form.protocol" :options="STREAM_PROTOCOL_OPTIONS" />
            </Form.Item>
          </div>

          <div class="flex items-start gap-2">
            <Form.Item class="flex-1" label="监听端口" required>
              <InputNumber
                v-model:value="form.listenPort"
                :max="65535"
                :min="1"
                class="w-full"
                placeholder="如 3306"
              />
            </Form.Item>
            <Form.Item class="flex-1" label="目标地址" required>
              <Input v-model:value="form.upstreamHost" placeholder="如 127.0.0.1" />
            </Form.Item>
            <Form.Item class="w-32" label="目标端口" required>
              <InputNumber
                v-model:value="form.upstreamPort"
                :max="65535"
                :min="1"
                class="w-full"
                placeholder="3306"
              />
            </Form.Item>
          </div>

          <div class="flex items-start gap-2">
            <Form.Item class="w-44" label="超时（秒）">
              <InputNumber
                v-model:value="form.proxyTimeout"
                :max="3600"
                :min="1"
                class="w-full"
              />
            </Form.Item>
            <Form.Item class="flex-1" label="备注">
              <Input v-model:value="form.remark" placeholder="选填" />
            </Form.Item>
          </div>
        </Form>

        <Space>
          <Button :loading="saving" type="primary" @click="save">保存</Button>
          <Button @click="cancelEdit">返回列表</Button>
        </Space>
      </template>
    </Spin>

    <DangerConfirmModal
      v-model:open="dangerOpen"
      :keyword="dangerTarget?.name ?? ''"
      description="删除四层转发将立即中断该端口转发，需二次确认。"
      title="删除四层转发"
      @confirm="doDelete"
    />
  </Drawer>
</template>
