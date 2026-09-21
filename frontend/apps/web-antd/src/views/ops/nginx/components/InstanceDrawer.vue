<script lang="ts" setup>
/**
 * Nginx 实例管理抽屉。
 *
 * <p>支持多实例：左侧实例列表，右侧表单（自动探测 / 手工指定）。核心价值是「可指定
 * 要管理的 nginx 实例」——没有配置时后端会自动探测主机默认 nginx；这里让使用者
 * 显式增删改实例、切换默认实例。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { NginxApi } from '#/api';

import { ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Space,
  Spin,
  Switch,
  Tag,
  message,
} from 'ant-design-vue';

import {
  deleteNginxInstanceApi,
  detectNginxInstanceApi,
  getNginxInstancesApi,
  saveNginxInstanceApi,
  setDefaultNginxInstanceApi,
  updateNginxInstanceApi,
} from '#/api';

defineOptions({ name: 'OpsNginxInstanceDrawer' });

const props = defineProps<{
  open: boolean;
  /** 当前选中的实例 ID（高亮） */
  currentId?: string;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  /** 实例有增删改 / 默认实例切换，父组件需刷新状态与列表 */
  changed: [string?];
}>();

function blank(): NginxApi.NginxInstanceBody {
  return {
    name: '',
    detectMode: 'auto',
    binaryPath: '',
    prefix: '',
    confPath: '',
    managedDir: '',
    streamDir: '',
    certDir: '',
    acmeWebroot: '',
    logDir: '',
    status: 1,
    remark: '',
  };
}

const loading = ref(false);
const instances = ref<NginxApi.NginxInstance[]>([]);
const selectedId = ref<string>();
const form = ref<NginxApi.NginxInstanceBody>(blank());
const saving = ref(false);
const detecting = ref(false);

async function load() {
  loading.value = true;
  try {
    instances.value = (await getNginxInstancesApi()) ?? [];
    const first = instances.value[0];
    if (!selectedId.value && first) {
      select(first);
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    selectedId.value = props.currentId;
    void load();
  },
);

function select(inst: NginxApi.NginxInstance) {
  selectedId.value = inst.id;
  form.value = {
    ...blank(),
    id: inst.id,
    name: inst.name,
    detectMode: inst.detectMode ?? 'manual',
    binaryPath: inst.binaryPath,
    prefix: inst.prefix,
    confPath: inst.confPath,
    managedDir: inst.managedDir,
    streamDir: inst.streamDir,
    certDir: inst.certDir,
    acmeWebroot: inst.acmeWebroot,
    logDir: inst.logDir,
    status: inst.status ?? 1,
    remark: inst.remark,
  };
}

function createNew() {
  selectedId.value = undefined;
  form.value = blank();
}

async function doDetect() {
  detecting.value = true;
  try {
    const d = await detectNginxInstanceApi();
    if (d) {
      form.value = { ...blank(), ...d };
      form.value.name = form.value.name || '主机默认 nginx';
      message.success('已探测到主机 nginx，请核对后保存');
    }
  } finally {
    detecting.value = false;
  }
}

function onStatusChange(checked: boolean | number | string) {
  form.value.status = checked ? 1 : 0;
}

async function save() {
  if (!form.value.name?.trim()) {
    message.warning('请填写实例名');
    return;
  }
  saving.value = true;
  try {
    if (form.value.id) {
      await updateNginxInstanceApi(form.value);
      message.success('实例已更新');
    } else {
      await saveNginxInstanceApi(form.value);
      message.success('实例已创建');
    }
    await load();
    emit('changed', form.value.id);
  } finally {
    saving.value = false;
  }
}

function confirmDelete(inst: NginxApi.NginxInstance) {
  if (inst.defaultFlag === 1) {
    message.warning('默认实例不可删除，请先将其它实例设为默认');
    return;
  }
  Modal.confirm({
    title: `删除实例「${inst.name}」？`,
    content: '删除后该实例下的站点/上游/证书等托管配置将无法继续管理（已写入磁盘的配置不会被清除）。',
    okText: '删除',
    okType: 'danger',
    async onOk() {
      await deleteNginxInstanceApi(inst.id as string);
      message.success('实例已删除');
      if (selectedId.value === inst.id) {
        selectedId.value = undefined;
        form.value = blank();
      }
      await load();
      emit('changed');
    },
  });
}

async function setDefault(inst: NginxApi.NginxInstance) {
  if (inst.defaultFlag === 1) return;
  await setDefaultNginxInstanceApi(inst.id as string);
  message.success(`已将「${inst.name}」设为默认`);
  await load();
  emit('changed', inst.id);
}
</script>

<template>
  <Drawer
    :open="open"
    :width="760"
    title="Nginx 实例"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <div class="grid grid-cols-[220px_1fr] gap-4">
        <!-- 实例列表 -->
        <div class="space-y-1 border-r border-gray-200 pr-3 dark:border-gray-700">
          <Button block size="small" type="primary" @click="createNew">新建实例</Button>
          <div
            v-for="inst in instances"
            :key="inst.id"
            class="cursor-pointer rounded border p-2 transition"
            :class="[
              selectedId === inst.id
                ? 'border-blue-500 bg-blue-50 dark:bg-blue-950/30'
                : 'border-gray-200 dark:border-gray-700',
            ]"
            @click="select(inst)"
          >
            <div class="flex items-center gap-1">
              <span class="truncate text-sm font-medium">{{ inst.name }}</span>
              <Tag v-if="inst.defaultFlag === 1" color="gold" class="!mr-0">默认</Tag>
            </div>
            <div class="mt-1 flex items-center gap-2 text-xs text-gray-400">
              <span>{{ inst.detectMode === 'auto' ? '自动探测' : '手工指定' }}</span>
              <span>{{ inst.status === 1 ? '启用' : '停用' }}</span>
            </div>
          </div>
          <Empty
            v-if="instances.length === 0"
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
            description="暂无实例"
          />
        </div>

        <!-- 表单 -->
        <div>
          <Alert
            class="mb-3"
            message="不指定实例时，后端会自动探测主机已安装的 nginx 作为默认实例。"
            show-icon
            type="info"
          />
          <Form layout="vertical">
            <Form.Item label="实例名" required>
              <Input v-model:value="form.name" placeholder="如 主机默认 nginx" />
            </Form.Item>

            <Form.Item label="实例来源">
              <Radio.Group v-model:value="form.detectMode">
                <Radio value="auto">自动探测</Radio>
                <Radio value="manual">手工指定</Radio>
              </Radio.Group>
            </Form.Item>

            <div class="flex items-start gap-2">
              <Form.Item class="flex-1" label="nginx 可执行路径">
                <Input v-model:value="form.binaryPath" placeholder="/usr/bin/nginx" />
              </Form.Item>
              <Form.Item class="flex-1" label="--prefix">
                <Input v-model:value="form.prefix" placeholder="/www/server/nginx" />
              </Form.Item>
            </div>

            <Form.Item label="主配置 nginx.conf">
              <Input
                v-model:value="form.confPath"
                placeholder="/www/server/nginx/conf/nginx.conf"
              />
            </Form.Item>

            <Form.Item label="托管站点目录">
              <Input
                v-model:value="form.managedDir"
                placeholder="<prefix>/conf/serverpanel.d"
              />
            </Form.Item>

            <div class="flex items-start gap-2">
              <Form.Item class="flex-1" label="stream 托管目录">
                <Input v-model:value="form.streamDir" placeholder="<managed>/stream" />
              </Form.Item>
              <Form.Item class="flex-1" label="证书目录">
                <Input v-model:value="form.certDir" placeholder="<managed>/certs" />
              </Form.Item>
            </div>

            <div class="flex items-start gap-2">
              <Form.Item class="flex-1" label="ACME webroot">
                <Input v-model:value="form.acmeWebroot" placeholder="/www/wwwroot/psm-acme" />
              </Form.Item>
              <Form.Item class="flex-1" label="日志目录">
                <Input v-model:value="form.logDir" placeholder="/www/wwwlogs" />
              </Form.Item>
            </div>

            <div class="flex items-start gap-2">
              <Form.Item class="flex-1" label="启用">
                <Switch
                  :checked="form.status === 1"
                  @change="onStatusChange"
                />
              </Form.Item>
            </div>

            <Form.Item label="备注">
              <Input v-model:value="form.remark" placeholder="选填" />
            </Form.Item>
          </Form>

          <Space>
            <Button :loading="saving" type="primary" @click="save">
              {{ form.id ? '保存修改' : '创建实例' }}
            </Button>
            <Button :loading="detecting" @click="doDetect">探测主机 nginx</Button>
            <Button
              v-if="form.id"
              :disabled="instances.find((i) => i.id === form.id)?.defaultFlag === 1"
              @click="setDefault(instances.find((i) => i.id === form.id)!)"
            >
              设为默认
            </Button>
            <Button
              v-if="form.id"
              danger
              @click="confirmDelete(instances.find((i) => i.id === form.id)!)"
            >
              删除
            </Button>
          </Space>
        </div>
      </div>
    </Spin>
  </Drawer>
</template>
