<script lang="ts" setup>
/**
 * 站点表单（新建 / 编辑）——反向代理与静态站点。
 *
 * <p>覆盖：多域名、类型（proxy/static）、上游（引用组 / 内联）、SSL 三态
 * （off / Let's Encrypt / 手动证书，需选证书）、80→443 跳转、HSTS、自定义
 * location 编辑器，以及「渲染预览」（调用后端 FreeMarker 渲染，不落盘）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { NginxApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Collapse,
  Divider,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Spin,
  Switch,
  message,
} from 'ant-design-vue';

import {
  getNginxCertPageApi,
  getNginxUpstreamPageApi,
  previewNginxSiteApi,
} from '#/api';

import { SSL_MODE_OPTIONS, SITE_TYPE_OPTIONS } from '../utils';

import LocationEditor from './LocationEditor.vue';

defineOptions({ name: 'OpsNginxSiteFormModal' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
  /** 编辑时传入；新建为 null */
  site?: NginxApi.NginxSite | null;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  submit: [NginxApi.NginxSiteBody];
}>();

interface SiteForm {
  id?: string;
  name: string;
  domainsText: string;
  siteType: 'proxy' | 'static';
  upstreamMode: 'group' | 'inline';
  upstreamId?: string;
  upstreamInline: string;
  staticRoot: string;
  sslMode: 'off' | 'letsencrypt' | 'custom';
  certId?: string;
  httpRedirect: boolean;
  hsts: boolean;
  locations: NginxApi.NginxLocation[];
  remark: string;
}

function blank(): SiteForm {
  return {
    name: '',
    domainsText: '',
    siteType: 'proxy',
    upstreamMode: 'group',
    upstreamId: undefined,
    upstreamInline: '',
    staticRoot: '',
    sslMode: 'off',
    certId: undefined,
    httpRedirect: true,
    hsts: false,
    locations: [],
    remark: '',
  };
}

const form = ref<SiteForm>(blank());
const upstreams = ref<NginxApi.NginxUpstream[]>([]);
const certs = ref<NginxApi.NginxCert[]>([]);
const loadingOpts = ref(false);
const previewing = ref(false);
const preview = ref('');

const editId = computed(() => form.value.id);

const upstreamOptions = computed(() =>
  upstreams.value.map((u) => ({ label: u.name, value: u.id as string })),
);

const certOptions = computed(() =>
  certs.value.map((c) => ({
    label: `${c.domain}（${c.type === 'letsencrypt' ? "Let's Encrypt" : '手动'}）`,
    value: c.id as string,
  })),
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    preview.value = '';
    const s = props.site;
    if (s) {
      let locations: NginxApi.NginxLocation[] = [];
      try {
        locations = s.locationsJson ? JSON.parse(s.locationsJson) : [];
      } catch {
        locations = [];
      }
      form.value = {
        id: s.id,
        name: s.name,
        domainsText: s.domains ?? '',
        siteType: s.siteType ?? 'proxy',
        upstreamMode: s.upstreamId ? 'group' : 'inline',
        upstreamId: s.upstreamId,
        upstreamInline: s.upstreamInline ?? '',
        staticRoot: s.staticRoot ?? '',
        sslMode: s.sslMode ?? 'off',
        certId: s.certId,
        httpRedirect: s.httpRedirect !== 0,
        hsts: s.hsts === 1,
        locations: Array.isArray(locations) ? locations : [],
        remark: s.remark ?? '',
      };
    } else {
      form.value = blank();
    }
    void loadOptions();
  },
);

async function loadOptions() {
  loadingOpts.value = true;
  try {
    const [u, c] = await Promise.all([
      getNginxUpstreamPageApi({ instanceId: props.instanceId, pageNum: 1, pageSize: 100 }),
      getNginxCertPageApi({ instanceId: props.instanceId, pageNum: 1, pageSize: 100 }),
    ]);
    upstreams.value = u?.records ?? [];
    certs.value = c?.records ?? [];
  } finally {
    loadingOpts.value = false;
  }
}

function splitDomains(text: string): string[] {
  return text
    .split(/[\s,，;；]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 组装请求体（供提交与预览共用） */
function buildBody(): NginxApi.NginxSiteBody {
  const f = form.value;
  const body: NginxApi.NginxSiteBody = {
    id: f.id,
    instanceId: props.instanceId,
    name: f.name.trim(),
    domains: splitDomains(f.domainsText),
    siteType: f.siteType,
    sslMode: f.sslMode,
    httpRedirect: f.httpRedirect,
    hsts: f.hsts,
    locations: f.locations,
    remark: f.remark.trim() || undefined,
  };
  if (f.siteType === 'proxy') {
    if (f.upstreamMode === 'group') {
      body.upstreamId = f.upstreamId;
    } else {
      body.upstreamInline = f.upstreamInline.trim() || undefined;
    }
  } else {
    body.staticRoot = f.staticRoot.trim() || undefined;
  }
  if (f.sslMode !== 'off') {
    body.certId = f.certId;
  }
  return body;
}

/** 客户端预校验，返回错误文案（空串表示通过） */
function validate(): string {
  const f = form.value;
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(f.name.trim())) {
    return '站点名仅支持字母/数字/-/_，且以字母或数字开头';
  }
  if (splitDomains(f.domainsText).length === 0) {
    return '至少填写一个域名';
  }
  if (f.siteType === 'proxy') {
    if (f.upstreamMode === 'group' && !f.upstreamId) {
      return '请选择上游组';
    }
    if (f.upstreamMode === 'inline' && !f.upstreamInline.trim()) {
      return '请填写内联上游地址';
    }
  } else if (!f.staticRoot.trim()) {
    return '请填写静态站点根目录';
  }
  if (f.sslMode !== 'off' && !f.certId) {
    return '开启 HTTPS 必须选择证书';
  }
  return '';
}

const canSubmit = computed(() => validate() === '');

async function doPreview() {
  const err = validate();
  if (err) {
    message.warning(err);
    return;
  }
  previewing.value = true;
  try {
    preview.value = await previewNginxSiteApi(buildBody());
  } finally {
    previewing.value = false;
  }
}

function onSubmit() {
  const err = validate();
  if (err) {
    message.warning(err);
    return;
  }
  emit('submit', buildBody());
}
</script>

<template>
  <Modal
    :ok-button-props="{ disabled: !canSubmit }"
    :open="open"
    :width="760"
    :title="editId ? '编辑站点' : '新建站点'"
    ok-text="保存并生效"
    @cancel="emit('update:open', false)"
    @ok="onSubmit"
  >
    <Spin :spinning="loadingOpts">
      <Form class="mt-2" layout="vertical">
        <div class="flex items-start gap-2">
          <Form.Item class="w-56" label="站点名" required>
            <Input v-model:value="form.name" placeholder="如 myapp" />
          </Form.Item>
          <Form.Item class="flex-1" label="域名（逗号/换行分隔，首个为主域）" required>
            <Input
              v-model:value="form.domainsText"
              placeholder="example.com, www.example.com"
            />
          </Form.Item>
        </div>

        <Form.Item label="站点类型">
          <Radio.Group v-model:value="form.siteType" :options="SITE_TYPE_OPTIONS" />
        </Form.Item>

        <template v-if="form.siteType === 'proxy'">
          <Form.Item label="上游来源">
            <Radio.Group v-model:value="form.upstreamMode">
              <Radio value="group">引用上游组</Radio>
              <Radio value="inline">内联上游</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            v-if="form.upstreamMode === 'group'"
            label="上游组"
            required
          >
            <Select
              v-model:value="form.upstreamId"
              :options="upstreamOptions"
              placeholder="选择上游组"
              show-search
            />
          </Form.Item>
          <Form.Item
            v-else
            label="内联上游"
            required
          >
            <Input
              v-model:value="form.upstreamInline"
              placeholder="如 http://127.0.0.1:3000"
            />
          </Form.Item>
        </template>

        <Form.Item v-else label="静态站点根目录" required>
          <Input v-model:value="form.staticRoot" placeholder="如 /www/wwwroot/myapp" />
        </Form.Item>

        <Divider class="my-2" />

        <div class="flex items-start gap-2">
          <Form.Item class="w-56" label="SSL 模式">
            <Select v-model:value="form.sslMode" :options="SSL_MODE_OPTIONS" />
          </Form.Item>
          <Form.Item
            v-if="form.sslMode !== 'off'"
            class="flex-1"
            label="证书"
            required
          >
            <Select
              v-model:value="form.certId"
              :options="certOptions"
              placeholder="选择证书"
              show-search
            />
          </Form.Item>
        </div>

        <div class="flex items-center gap-8">
          <span class="flex items-center gap-2 text-sm">
            <Switch v-model:checked="form.httpRedirect" size="small" />
            80 → 443 强制跳转
          </span>
          <span class="flex items-center gap-2 text-sm">
            <Switch v-model:checked="form.hsts" size="small" />
            启用 HSTS
          </span>
        </div>

        <Divider class="my-2" />

        <Form.Item label="自定义 location">
          <LocationEditor v-model:value="form.locations" />
        </Form.Item>

        <Form.Item label="备注">
          <Input v-model:value="form.remark" placeholder="选填" />
        </Form.Item>
      </Form>

      <Alert
        class="mb-2"
        message="保存会立即渲染配置 → nginx -t 校验 → reload 生效；校验失败会自动回滚。"
        show-icon
        type="info"
      />

      <div class="flex items-center gap-2">
        <Button :loading="previewing" size="small" @click="doPreview">渲染预览</Button>
      </div>
      <Collapse v-if="preview" class="mt-2">
        <Collapse.Panel key="preview" header="渲染结果预览">
          <pre
            class="max-h-[300px] overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-gray-900"
          >{{ preview }}</pre>
        </Collapse.Panel>
      </Collapse>
    </Spin>
  </Modal>
</template>
