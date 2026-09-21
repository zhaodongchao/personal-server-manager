<script lang="ts" setup>
/**
 * 上游组管理抽屉（负载均衡后端池）。
 *
 * <p>自包含：加载当前实例的上游组列表，支持新建/编辑/删除。删除会先校验
 * 「是否仍被站点引用」（后端返回 6013 NGINX_UPSTREAM_IN_USE 则拒绝），并需键入
 * 上游组名二次确认。
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
  Switch,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import {
  deleteNginxUpstreamApi,
  getNginxUpstreamPageApi,
  saveNginxUpstreamApi,
  updateNginxUpstreamApi,
} from '#/api';

import { STRATEGY_OPTIONS, describeServers, strategyLabel } from '../utils';

import DangerConfirmModal from './DangerConfirmModal.vue';

defineOptions({ name: 'OpsNginxUpstreamDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
  canWrite?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  changed: [];
}>();

interface ServerForm {
  host: string;
  port: number;
  weight?: number;
  maxFails?: number;
  backup?: boolean;
}

interface UpstreamForm {
  id?: string;
  name: string;
  strategy: string;
  servers: ServerForm[];
  keepalive: number;
  remark: string;
}

function blank(): UpstreamForm {
  return {
    name: '',
    strategy: 'round_robin',
    servers: [{ host: '', port: 80, weight: 1, maxFails: 3, backup: false }],
    keepalive: 0,
    remark: '',
  };
}

const loading = ref(false);
const rows = ref<NginxApi.NginxUpstream[]>([]);
const editing = ref(false);
const form = ref<UpstreamForm>(blank());
const saving = ref(false);

const dangerOpen = ref(false);
const dangerTarget = ref<NginxApi.NginxUpstream | null>(null);

async function load() {
  loading.value = true;
  try {
    const res = await getNginxUpstreamPageApi({
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

function toForm(u: NginxApi.NginxUpstream): UpstreamForm {
  let servers: ServerForm[] = [];
  try {
    servers = u.serversJson ? JSON.parse(u.serversJson) : [];
  } catch {
    servers = [];
  }
  return {
    id: u.id,
    name: u.name,
    strategy: u.strategy ?? 'round_robin',
    servers:
      servers.length > 0
        ? servers.map((s) => ({
            host: s.host ?? '',
            port: s.port ?? 80,
            weight: s.weight ?? 1,
            maxFails: s.maxFails ?? 3,
            backup: !!s.backup,
          }))
        : blank().servers,
    keepalive: u.keepalive ?? 0,
    remark: u.remark ?? '',
  };
}

function createNew() {
  form.value = blank();
  editing.value = true;
}

function edit(u: NginxApi.NginxUpstream) {
  form.value = toForm(u);
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
  form.value = blank();
}

function addServer() {
  form.value.servers.push({ host: '', port: 80, weight: 1, maxFails: 3, backup: false });
}

function removeServer(idx: number) {
  form.value.servers.splice(idx, 1);
}

async function save() {
  if (!form.value.name.trim()) {
    message.warning('请填写上游组名');
    return;
  }
  const servers = form.value.servers
    .filter((s) => s.host.trim())
    .map((s) => ({ ...s, host: s.host.trim() }));
  if (servers.length === 0) {
    message.warning('至少需要一个后端');
    return;
  }
  const body: NginxApi.NginxUpstreamBody = {
    id: form.value.id,
    instanceId: props.instanceId,
    name: form.value.name.trim(),
    strategy: form.value.strategy,
    servers,
    keepalive: form.value.keepalive,
    remark: form.value.remark,
  };
  saving.value = true;
  try {
    if (form.value.id) {
      await updateNginxUpstreamApi(body);
      message.success('上游组已更新');
    } else {
      await saveNginxUpstreamApi(body);
      message.success('上游组已创建');
    }
    editing.value = false;
    form.value = blank();
    await load();
    emit('changed');
  } finally {
    saving.value = false;
  }
}

function askDelete(u: NginxApi.NginxUpstream) {
  dangerTarget.value = u;
  dangerOpen.value = true;
}

async function doDelete(keyword: string) {
  const u = dangerTarget.value;
  if (!u) return;
  await deleteNginxUpstreamApi(u.id as string, keyword);
  message.success(`上游组「${u.name}」已删除`);
  await load();
  emit('changed');
}

const columns = [
  { dataIndex: 'name', title: '名称', width: 160 },
  { dataIndex: 'strategy', title: '策略', width: 110 },
  { dataIndex: 'servers', title: '后端' },
  { dataIndex: 'keepalive', title: 'keepalive', width: 96 },
  { dataIndex: 'actions', title: '操作', width: 120 },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="680"
    title="上游组（负载均衡）"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <!-- 列表 -->
      <template v-if="!editing">
        <div class="mb-3 flex items-center justify-between">
          <span class="text-sm text-gray-500">
            共 {{ rows.length }} 个上游组
          </span>
          <Button v-if="canWrite" size="small" type="primary" @click="createNew">
            新建上游组
          </Button>
        </div>
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          :row-key="(r: NginxApi.NginxUpstream) => r.id as string"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'name'">
              <span class="font-medium">{{ (record as NginxApi.NginxUpstream).name }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'strategy'">
              <Tag color="blue">
                {{ strategyLabel((record as NginxApi.NginxUpstream).strategy) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'servers'">
              <span class="break-all font-mono text-xs">
                {{ describeServers((record as NginxApi.NginxUpstream).serversJson) }}
              </span>
            </template>
            <template v-else-if="column.dataIndex === 'keepalive'">
              {{ (record as NginxApi.NginxUpstream).keepalive ?? 0 }}
            </template>
            <template v-else-if="column.dataIndex === 'actions'">
              <Space v-if="canWrite" :size="0">
                <Button
                  size="small"
                  type="link"
                  @click="edit(record as NginxApi.NginxUpstream)"
                >
                  编辑
                </Button>
                <Button
                  danger
                  size="small"
                  type="link"
                  @click="askDelete(record as NginxApi.NginxUpstream)"
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
          description="暂无上游组"
        />
      </template>

      <!-- 表单 -->
      <template v-else>
        <Form layout="vertical">
          <div class="flex items-start gap-2">
            <Form.Item class="flex-1" label="上游组名" required>
              <Input v-model:value="form.name" placeholder="如 app_backend" />
            </Form.Item>
            <Form.Item class="w-48" label="负载策略">
              <Select v-model:value="form.strategy" :options="STRATEGY_OPTIONS" />
            </Form.Item>
          </div>

          <Form.Item label="后端服务器">
            <div class="space-y-2">
              <div
                v-for="(s, idx) in form.servers"
                :key="idx"
                class="flex items-center gap-2"
              >
                <Input
                  v-model:value="s.host"
                  class="flex-1"
                  placeholder="host，如 127.0.0.1"
                />
                <InputNumber
                  v-model:value="s.port"
                  :max="65535"
                  :min="1"
                  class="w-24"
                  placeholder="端口"
                />
                <InputNumber
                  v-model:value="s.weight"
                  :max="100"
                  :min="1"
                  class="w-20"
                  placeholder="权重"
                />
                <span class="flex items-center gap-1 text-xs text-gray-400">
                  备用
                  <Switch v-model:checked="s.backup" size="small" />
                </span>
                <Button danger size="small" type="text" @click="removeServer(idx)">
                  删
                </Button>
              </div>
              <Button size="small" type="dashed" @click="addServer">添加后端</Button>
            </div>
          </Form.Item>

          <div class="flex items-start gap-2">
            <Form.Item class="w-40" label="keepalive">
              <InputNumber v-model:value="form.keepalive" :min="0" class="w-full" />
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
      description="删除上游组后，引用它的站点将失去后端，需二次确认。"
      title="删除上游组"
      @confirm="doDelete"
    />
  </Drawer>
</template>
