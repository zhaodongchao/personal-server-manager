<script lang="ts" setup>
/**
 * SSL 证书管理抽屉。
 *
 * <p>自包含：加载当前实例证书列表，支持 Let's Encrypt（HTTP-01）自动申请、
 * 手动上传 PEM、续期、删除（需键入域名二次确认）。到期状态后端已按
 * valid/expiring/expired 归类，前端据此红黄标色。
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
  Radio,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import {
  deleteNginxCertApi,
  getNginxCertPageApi,
  issueNginxCertApi,
  renewNginxCertApi,
  uploadNginxCertApi,
} from '#/api';

import { certStatusColor, certStatusLabel, fmtTime } from '../utils';

import DangerConfirmModal from './DangerConfirmModal.vue';

defineOptions({ name: 'OpsNginxCertDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
  canWrite?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  changed: [];
}>();

interface CertForm {
  type: 'letsencrypt' | 'custom';
  domain: string;
  email: string;
  autoRenew: boolean;
  certContent: string;
  keyContent: string;
}

function blank(): CertForm {
  return {
    type: 'letsencrypt',
    domain: '',
    email: '',
    autoRenew: true,
    certContent: '',
    keyContent: '',
  };
}

const loading = ref(false);
const rows = ref<NginxApi.NginxCert[]>([]);
const editing = ref(false);
const form = ref<CertForm>(blank());
const saving = ref(false);

const dangerOpen = ref(false);
const dangerTarget = ref<NginxApi.NginxCert | null>(null);

async function load() {
  loading.value = true;
  try {
    const res = await getNginxCertPageApi({
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

function cancelEdit() {
  editing.value = false;
  form.value = blank();
}

async function submit() {
  const domain = form.value.domain.trim();
  if (!domain) {
    message.warning('请填写域名');
    return;
  }
  saving.value = true;
  try {
    if (form.value.type === 'letsencrypt') {
      const res = await issueNginxCertApi({
        instanceId: props.instanceId,
        domain,
        email: form.value.email.trim() || undefined,
        mode: 'http01',
        autoRenew: form.value.autoRenew ? 1 : 0,
      });
      message.success(res?.message ?? `证书「${domain}」申请成功`);
    } else {
      if (!form.value.certContent.trim() || !form.value.keyContent.trim()) {
        message.warning('请提供证书与私钥内容');
        return;
      }
      const res = await uploadNginxCertApi({
        instanceId: props.instanceId,
        domain,
        certContent: form.value.certContent,
        keyContent: form.value.keyContent,
      });
      message.success(res?.message ?? `证书「${domain}」已上传`);
    }
    editing.value = false;
    form.value = blank();
    await load();
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function renew(c: NginxApi.NginxCert) {
  const res = await renewNginxCertApi(c.id as string);
  message.success(res?.message ?? `证书「${c.domain}」续期成功`);
  await load();
  emit('changed');
}

function askDelete(c: NginxApi.NginxCert) {
  dangerTarget.value = c;
  dangerOpen.value = true;
}

async function doDelete(keyword: string) {
  const c = dangerTarget.value;
  if (!c) return;
  await deleteNginxCertApi(c.id as string, keyword);
  message.success(`证书「${c.domain}」已删除`);
  await load();
  emit('changed');
}

const columns = [
  { dataIndex: 'domain', title: '域名' },
  { dataIndex: 'type', title: '类型', width: 120 },
  { dataIndex: 'issuer', title: '签发者', width: 120 },
  { dataIndex: 'notAfter', title: '到期时间', width: 160 },
  { dataIndex: 'status', title: '状态', width: 100 },
  { dataIndex: 'actions', title: '操作', width: 130 },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="720"
    title="SSL 证书"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <template v-if="!editing">
        <div class="mb-3 flex items-center justify-between">
          <span class="text-sm text-gray-500">共 {{ rows.length }} 张证书</span>
          <Button v-if="canWrite" size="small" type="primary" @click="createNew">
            申请 / 上传证书
          </Button>
        </div>
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          :row-key="(r: NginxApi.NginxCert) => r.id as string"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'domain'">
              <span class="font-medium">{{ (record as NginxApi.NginxCert).domain }}</span>
            </template>
            <template v-else-if="column.dataIndex === 'type'">
              <Tag :color="(record as NginxApi.NginxCert).type === 'letsencrypt' ? 'green' : 'blue'">
                {{ (record as NginxApi.NginxCert).type === 'letsencrypt' ? "Let's Encrypt" : '手动' }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'issuer'">
              {{ (record as NginxApi.NginxCert).issuer || '-' }}
            </template>
            <template v-else-if="column.dataIndex === 'notAfter'">
              {{ fmtTime((record as NginxApi.NginxCert).notAfter) }}
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <Tag :color="certStatusColor((record as NginxApi.NginxCert).status)">
                {{ certStatusLabel((record as NginxApi.NginxCert).status) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'actions'">
              <Space v-if="canWrite" :size="0">
                <Button
                  v-if="(record as NginxApi.NginxCert).type === 'letsencrypt'"
                  size="small"
                  type="link"
                  @click="renew(record as NginxApi.NginxCert)"
                >
                  续期
                </Button>
                <Button
                  danger
                  size="small"
                  type="link"
                  @click="askDelete(record as NginxApi.NginxCert)"
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
          description="暂无证书"
        />
      </template>

      <template v-else>
        <Form layout="vertical">
          <Form.Item label="证书类型">
            <Radio.Group v-model:value="form.type">
              <Radio value="letsencrypt">Let's Encrypt（自动申请）</Radio>
              <Radio value="custom">手动上传</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item label="域名" required>
            <Input v-model:value="form.domain" placeholder="如 example.com" />
          </Form.Item>

          <template v-if="form.type === 'letsencrypt'">
            <Alert
              class="mb-3"
              message="HTTP-01 通过 webroot 校验申请；DNS-01（通配符）将在后续版本开放。"
              show-icon
              type="info"
            />
            <div class="flex items-start gap-2">
              <Form.Item class="flex-1" label="联系邮箱">
                <Input v-model:value="form.email" placeholder="admin@example.com" />
              </Form.Item>
              <Form.Item label="自动续期">
                <Switch v-model:checked="form.autoRenew" />
              </Form.Item>
            </div>
          </template>

          <template v-else>
            <Form.Item label="证书内容（PEM）" required>
              <Input.TextArea
                v-model:value="form.certContent"
                :rows="5"
                placeholder="-----BEGIN CERTIFICATE-----"
              />
            </Form.Item>
            <Form.Item label="私钥内容（PEM）" required>
              <Input.TextArea
                v-model:value="form.keyContent"
                :rows="5"
                placeholder="-----BEGIN PRIVATE KEY-----"
              />
            </Form.Item>
          </template>
        </Form>

        <Space>
          <Button :loading="saving" type="primary" @click="submit">提交</Button>
          <Button @click="cancelEdit">返回列表</Button>
        </Space>
      </template>
    </Spin>

    <DangerConfirmModal
      v-model:open="dangerOpen"
      :keyword="dangerTarget?.domain ?? ''"
      description="删除证书后，引用它的站点将失去 HTTPS 证书，需二次确认。"
      title="删除证书"
      @confirm="doDelete"
    />
  </Drawer>
</template>
