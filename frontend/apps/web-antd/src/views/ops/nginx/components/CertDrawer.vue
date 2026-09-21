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
  dnsVerifyNginxCertApi,
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
  /** letsencrypt 下生效：http01 / dns01（通配符） */
  mode: 'http01' | 'dns01';
  domain: string;
  email: string;
  autoRenew: boolean;
  certContent: string;
  keyContent: string;
}

interface DnsPending {
  certId: string;
  name: string;
  value: string;
}

function blank(): CertForm {
  return {
    type: 'letsencrypt',
    mode: 'http01',
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

/** DNS-01 两步流：首步返回 TXT 后等待用户添加，期间展示验证按钮 */
const dnsPending = ref<DnsPending | null>(null);
const verifying = ref(false);

async function copyText(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text);
    message.success(`${label}已复制`);
  } catch {
    message.warning('复制失败，请手动选择复制');
  }
}

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
    dnsPending.value = null;
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
  dnsPending.value = null;
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
        mode: form.value.mode,
        autoRenew: form.value.autoRenew ? 1 : 0,
      });
      if (form.value.mode === 'dns01' && res?.dnsTxtName) {
        // 两步流：停留在编辑态，展示需添加的 TXT 记录，等待用户点击「验证并签发」
        dnsPending.value = {
          certId: (res?.changeId as unknown as string) ?? '',
          name: res.dnsTxtName,
          value: res.dnsTxtValue ?? '',
        };
        message.info(res?.message ?? '请添加 TXT 记录后点击「验证并签发」');
        saving.value = false;
        return;
      }
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

/** DNS-01 二步：用户添加 TXT 后唤醒 certbot 完成签发（后端轮询 acmeStatus，最长约 5 分钟） */
async function verifyDns() {
  if (!dnsPending.value) return;
  verifying.value = true;
  try {
    const res = await dnsVerifyNginxCertApi(dnsPending.value.certId);
    message.success(res?.message ?? '通配符证书已签发成功');
    dnsPending.value = null;
    editing.value = false;
    form.value = blank();
    await load();
    emit('changed');
  } catch (e) {
    message.error('签发未成功，请确认 TXT 已生效（DNS 全球生效可能需数分钟）后重试');
  } finally {
    verifying.value = false;
  }
}

async function renew(c: NginxApi.NginxCert) {
  const res = await renewNginxCertApi(c.id as string);
  message.success(res?.message ?? `证书「${c.domain}」续期成功`);
  await load();
  emit('changed');
}

/** 对处于 pending 的 DNS-01 证书再次唤醒验证（用户已添加 TXT 后） */
async function resumeVerify(c: NginxApi.NginxCert) {
  verifying.value = true;
  try {
    const res = await dnsVerifyNginxCertApi(c.id as string);
    message.success(res?.message ?? '通配符证书已签发成功');
    await load();
    emit('changed');
  } catch (e) {
    message.error('签发未成功，请确认 TXT 已生效（DNS 全球生效可能需数分钟）后重试');
  } finally {
    verifying.value = false;
  }
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
                  v-if="(record as NginxApi.NginxCert).status === 'pending'"
                  size="small"
                  type="link"
                  @click="resumeVerify(record as NginxApi.NginxCert)"
                >
                  继续验证
                </Button>
                <Button
                  v-else-if="(record as NginxApi.NginxCert).type === 'letsencrypt'"
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
            <Form.Item label="申请方式">
              <Radio.Group v-model:value="form.mode" :disabled="!!dnsPending">
                <Radio value="http01">HTTP-01（Webroot，单域名）</Radio>
                <Radio value="dns01">DNS-01（通配符 *.example.com）</Radio>
              </Radio.Group>
            </Form.Item>

            <template v-if="form.mode === 'http01'">
              <Alert
                class="mb-3"
                message="HTTP-01 通过 webroot 校验申请，需 80 端口可访问；适合普通单域名。"
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
              <Alert
                class="mb-3"
                message="DNS-01 用于通配符证书，需在 DNS 服务商添加一条 TXT 记录完成校验；适合 *.example.com。"
                show-icon
                type="info"
              />
              <Form.Item label="联系邮箱">
                <Input v-model:value="form.email" placeholder="admin@example.com" />
              </Form.Item>
              <Form.Item label="自动续期">
                <Switch v-model:checked="form.autoRenew" />
              </Form.Item>

              <template v-if="dnsPending">
                <Alert
                  :message="`请在 DNS 添加以下 TXT 记录，然后点击「验证并签发」：${dnsPending.name}`"
                  show-icon
                  type="warning"
                />
                <div class="mb-2 rounded border border-dashed border-gray-300 p-3">
                  <div class="mb-1 text-xs text-gray-500">记录类型：TXT</div>
                  <div class="mb-1 flex items-center justify-between">
                    <span class="text-xs text-gray-500">主机记录</span>
                    <Button
                      size="small"
                      type="link"
                      @click="copyText(dnsPending.name, '主机记录')"
                    >
                      复制
                    </Button>
                  </div>
                  <code class="block break-all text-sm">{{ dnsPending.name }}</code>
                  <div class="mb-1 mt-2 flex items-center justify-between">
                    <span class="text-xs text-gray-500">记录值</span>
                    <Button
                      size="small"
                      type="link"
                      @click="copyText(dnsPending.value, '记录值')"
                    >
                      复制
                    </Button>
                  </div>
                  <code class="block break-all text-sm">{{ dnsPending.value }}</code>
                </div>
                <Space>
                  <Button :loading="verifying" type="primary" @click="verifyDns">
                    验证并签发
                  </Button>
                  <Button :disabled="verifying" @click="cancelEdit">取消</Button>
                </Space>
              </template>
            </template>
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

        <Space v-if="!dnsPending">
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
