<script lang="ts" setup>
import type { ToolsApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  Alert,
  Button,
  Card,
  Input,
  Radio,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';

import { getJwtOptionsApi, signJwtApi, verifyJwtApi } from '#/api';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsJwt' });

const TextArea = Input.TextArea;

const SAMPLE_TOKEN =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
const SAMPLE_SECRET = 'your-256-bit-secret';
const DEFAULT_PAYLOAD = `{
  "sub": "1234567890",
  "name": "张三",
  "role": "admin"
}`;

const DEFAULT_PAYLOAD_PLACEHOLDER = '{"sub":"1","name":"张三","exp":1700000000}';

/** 注册声明含义（RFC 7519 七个 + OIDC 常用），时间类声明按秒解释 */
const CLAIM_DICT: Record<string, { desc: string; time?: boolean }> = {
  iss: { desc: '签发者（Issuer）：谁签发了该 token' },
  sub: { desc: '主题（Subject）：token 所描述的对象，通常是用户 ID' },
  aud: { desc: '受众（Audience）：该 token 的预期接收方' },
  exp: { desc: '过期时间（Expiration Time）：Unix 秒，超过后必须拒绝', time: true },
  nbf: { desc: '生效时间（Not Before）：Unix 秒，早于该时间必须拒绝', time: true },
  iat: { desc: '签发时间（Issued At）：Unix 秒', time: true },
  jti: { desc: 'JWT 唯一标识（JWT ID）：用于防重放' },
  name: { desc: 'OIDC：用户全名' },
  preferred_username: { desc: 'OIDC：用户名' },
  email: { desc: 'OIDC：邮箱' },
  email_verified: { desc: 'OIDC：邮箱是否已验证' },
  picture: { desc: 'OIDC：头像地址' },
  locale: { desc: 'OIDC：语言与地区' },
  updated_at: { desc: 'OIDC：信息更新时间', time: true },
  azp: { desc: 'OIDC：授权方（authorized party）' },
  sid: { desc: 'OIDC：会话 ID' },
  nonce: { desc: 'OIDC：防重放随机串' },
  scope: { desc: 'OAuth2：授权范围' },
  roles: { desc: '自定义：角色列表' },
};

interface ClaimRow {
  desc: string;
  name: string;
  status: string;
  statusColor: string;
  time: string;
  value: string;
}

interface ParsedJwt {
  alg: string;
  claims: ClaimRow[];
  error?: string;
  header: string;
  kid: string;
  payload: string;
  signature: string;
  timeLevel?: 'error' | 'info' | 'success' | 'warning';
  timeMessage?: string;
  typ: string;
  warnings: string[];
}

const claimColumns = [
  { dataIndex: 'name', key: 'name', title: '声明', width: 180 },
  { dataIndex: 'value', key: 'value', title: '取值', ellipsis: true },
  { dataIndex: 'time', key: 'time', title: '时间（本地）', width: 190 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 130 },
  { dataIndex: 'desc', key: 'desc', title: '说明' },
];

// ---------------- 可选清单（服务端下发） ----------------

const opts = ref<ToolsApi.JwtOptions>();
const algorithmOptions = computed(() =>
  (opts.value?.algorithms ?? []).map((a) => ({ label: a.label, value: a.value })),
);
const keyFormatOptions = computed(() =>
  (opts.value?.keyFormats ?? []).map((o) => ({ label: o.label, value: o.value })),
);
const secretEncodingOptions = computed(() =>
  (opts.value?.secretEncodings ?? []).map((o) => ({ label: o.label, value: o.value })),
);

// ---------------- 解码（纯前端） ----------------

const tab = ref('decode');
const token = ref('');

/** Base64URL -> 文本；非法编码返回 null */
function decodeSegment(segment: string): null | string {
  try {
    const normalized = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch {
    return null;
  }
}

function formatDuration(seconds: number): string {
  const abs = Math.abs(Math.floor(seconds));
  const day = Math.floor(abs / 86_400);
  const hour = Math.floor((abs % 86_400) / 3600);
  const minute = Math.floor((abs % 3600) / 60);
  if (day > 0) return `${day} 天 ${hour} 小时`;
  if (hour > 0) return `${hour} 小时 ${minute} 分`;
  return `${minute} 分`;
}

function toText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

const parsed = computed<ParsedJwt | undefined>(() => {
  const raw = token.value.trim();
  if (!raw) return undefined;
  const parts = raw.split('.');
  if (parts.length !== 3) {
    return {
      alg: '',
      claims: [],
      error: `JWT 应为 header.payload.signature 三段（以 . 分隔），当前为 ${parts.length} 段`,
      header: '',
      kid: '',
      payload: '',
      signature: '',
      typ: '',
      warnings: [],
    };
  }
  const headerText = decodeSegment(parts[0] as string);
  const payloadText = decodeSegment(parts[1] as string);
  const base = {
    alg: '',
    claims: [] as ClaimRow[],
    header: '',
    kid: '',
    payload: '',
    signature: parts[2] as string,
    typ: '',
    warnings: [] as string[],
  };
  if (headerText === null) return { ...base, error: 'Header 段不是合法的 Base64URL 编码' };
  if (payloadText === null) return { ...base, error: 'Payload 段不是合法的 Base64URL 编码' };

  let header: Record<string, unknown>;
  let payload: Record<string, unknown>;
  try {
    header = JSON.parse(headerText);
  } catch {
    return { ...base, error: 'Header 不是合法的 JSON' };
  }
  try {
    payload = JSON.parse(payloadText);
  } catch {
    return { ...base, error: 'Payload 不是合法的 JSON' };
  }
  if (typeof header !== 'object' || header === null || Array.isArray(header)) {
    return { ...base, error: 'Header 必须是 JSON 对象' };
  }
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
    return { ...base, error: 'Payload 必须是 JSON 对象' };
  }

  const now = Math.floor(Date.now() / 1000);
  const warnings: string[] = [];
  const alg = toText(header['alg']);
  if (!alg) warnings.push('Header 缺少 alg 声明');
  if (alg.toLowerCase() === 'none') {
    warnings.push('Header 声明 alg=none：该 token 没有签名，任何人都能伪造');
  }

  const claims: ClaimRow[] = [];
  for (const [name, raw_] of Object.entries(payload)) {
    const dict = CLAIM_DICT[name];
    const isTime = dict?.time === true;
    const seconds = isTime ? Number(raw_) : Number.NaN;
    const valid = isTime && Number.isFinite(seconds) && seconds > 0;
    let status = '—';
    let statusColor = 'default';
    if (isTime && !valid) {
      status = '非法值';
      statusColor = 'red';
    } else if (valid) {
      if (name === 'exp') {
        if (seconds <= now) {
          status = '已过期';
          statusColor = 'red';
        } else {
          status = `剩余 ${formatDuration(seconds - now)}`;
          statusColor = 'green';
        }
      } else if (name === 'nbf') {
        if (seconds > now) {
          status = `还有 ${formatDuration(seconds - now)} 生效`;
          statusColor = 'red';
        } else {
          status = '已生效';
          statusColor = 'green';
        }
      } else {
        status = seconds > now + 60 ? '晚于当前时间' : '正常';
        statusColor = seconds > now + 60 ? 'orange' : 'green';
      }
    }
    claims.push({
      desc: dict?.desc ?? '非注册声明（签发方自定义）',
      name,
      status,
      statusColor,
      time: valid ? new Date(seconds * 1000).toLocaleString('zh-CN') : '',
      value: toText(raw_),
    });
  }

  let timeLevel: ParsedJwt['timeLevel'] = 'success';
  let timeMessage = '时间声明有效';
  const exp = Number(payload['exp']);
  const nbf = Number(payload['nbf']);
  if (!Number.isFinite(exp) && !Number.isFinite(nbf)) {
    timeLevel = 'info';
    timeMessage = '该 token 未包含 exp / nbf，有效期由签发方另行约定';
  } else if (Number.isFinite(exp) && exp <= now) {
    timeLevel = 'error';
    timeMessage = `该 token 已过期 ${formatDuration(now - exp)}（exp=${exp}）`;
  } else if (Number.isFinite(nbf) && nbf > now) {
    timeLevel = 'warning';
    timeMessage = `该 token 尚未生效，还有 ${formatDuration(nbf - now)}（nbf=${nbf}）`;
  } else if (Number.isFinite(exp)) {
    timeMessage = `该 token 在有效期内，剩余 ${formatDuration(exp - now)}（exp=${exp}）`;
  }

  return {
    alg,
    claims,
    header: JSON.stringify(header, null, 2),
    kid: toText(header['kid']),
    payload: JSON.stringify(payload, null, 2),
    signature: parts[2] as string,
    timeLevel,
    timeMessage,
    typ: toText(header['typ']),
    warnings,
  };
});

function loadSample() {
  token.value = SAMPLE_TOKEN;
  keyFormat.value = 'SECRET';
  secretEncoding.value = 'TEXT';
  secret.value = SAMPLE_SECRET;
  verifyResult.value = undefined;
}

// ---------------- 验签（后端） ----------------

const keyFormat = ref('SECRET');
const secret = ref('');
const secretEncoding = ref('TEXT');

const verifying = ref(false);
const verifyResult = ref<ToolsApi.JwtVerifyResult>();

const keyPlaceholder = computed(() => {
  if (keyFormat.value === 'PEM') {
    return '-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...\n-----END PUBLIC KEY-----';
  }
  if (keyFormat.value === 'JWK') {
    return '{"kty":"oct","k":"c2VjcmV0"} 或 {"keys":[{"kty":"RSA","kid":"k1","n":"...","e":"AQAB"}]}';
  }
  return 'your-256-bit-secret';
});

const keyHint = computed(() => {
  if (keyFormat.value === 'PEM') {
    return '验签填公钥（-----BEGIN PUBLIC KEY-----）；签发填私钥（-----BEGIN PRIVATE KEY-----，PKCS#8）。PKCS#1 的 RSA 私钥请先用 openssl pkcs8 转换。';
  }
  if (keyFormat.value === 'JWK') {
    return '支持单个 JWK，也支持 JWKS（外层 {"keys":[...]}）—— JWKS 会按 token 中 header.kid 匹配，匹配不到则用第一个。';
  }
  return '对称密钥：按所选编码转换为字节后使用，HS* 的验签与签发用同一密钥。';
});

const keyRows = computed(() => (keyFormat.value === 'PEM' ? 8 : 4));

async function onVerify() {
  if (!token.value.trim()) {
    message.warning('请先粘贴待验证的 JWT');
    return;
  }
  if (!secret.value) {
    message.warning('请先填写密钥');
    return;
  }
  verifying.value = true;
  verifyResult.value = undefined;
  try {
    verifyResult.value = await verifyJwtApi({
      key: secret.value,
      keyFormat: keyFormat.value,
      secretEncoding: secretEncoding.value,
      token: token.value.trim(),
    });
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    verifying.value = false;
  }
}

// ---------------- 签发（后端） ----------------

const algorithm = ref('HS256');
const headerExtra = ref('');
const signPayload = ref(DEFAULT_PAYLOAD);
const signing = ref(false);
const signResult = ref<ToolsApi.JwtSignResult>();

const algorithmNote = computed(
  () =>
    opts.value?.algorithms.find((a) => a.value === algorithm.value)?.note ??
    '算法清单加载中…',
);

async function onSign() {
  if (!secret.value) {
    message.warning('请先填写签名密钥');
    return;
  }
  signing.value = true;
  signResult.value = undefined;
  try {
    signResult.value = await signJwtApi({
      algorithm: algorithm.value,
      header: headerExtra.value.trim() || undefined,
      key: secret.value,
      keyFormat: keyFormat.value,
      payload: signPayload.value,
      secretEncoding: secretEncoding.value,
    });
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    signing.value = false;
  }
}

/** 按当前时间写入 iat / exp（exp 默认 1 小时后） */
function fillTimes() {
  let payload: Record<string, unknown>;
  try {
    payload = JSON.parse(signPayload.value);
  } catch {
    message.error('Payload 不是合法的 JSON，无法写入 iat / exp');
    return;
  }
  const now = Math.floor(Date.now() / 1000);
  payload['iat'] = now;
  payload['exp'] = now + 3600;
  signPayload.value = JSON.stringify(payload, null, 2);
}

function useSignedToken() {
  if (!signResult.value) return;
  token.value = signResult.value.token;
  tab.value = 'decode';
  verifyResult.value = undefined;
}

onMounted(async () => {
  try {
    opts.value = await getJwtOptionsApi();
  } catch {
    message.error('算法清单加载失败，请刷新页面重试');
  }
});
</script>

<template>
  <Page
    title="JWT 工具"
    description="JWT 解码、签名验证与签发（HS / RS / PS / ES / EdDSA）— 解码在前端完成，密钥只发给后端做验签与签名，不落审计表"
  >
    <Tabs v-model:active-key="tab">
      <Tabs.TabPane key="decode" tab="解码 / 验签">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card size="small" title="Encoded Token">
            <TextArea
              v-model:value="token"
              :rows="14"
              allow-clear
              placeholder="粘贴 JWT（header.payload.signature）"
            />
            <div class="mt-3">
              <Space wrap>
                <Button :disabled="!token" @click="copyText(token)">
                  复制
                </Button>
                <Button @click="loadSample">载入示例</Button>
                <Button @click="token = ''">清空</Button>
              </Space>
            </div>
            <Alert
              v-if="parsed?.error"
              :message="parsed.error"
              class="mt-3"
              show-icon
              type="error"
            />
            <Alert
              v-for="w in parsed?.warnings ?? []"
              :key="w"
              :message="w"
              class="mt-3"
              show-icon
              type="warning"
            />
            <Alert
              v-if="parsed && !parsed.error"
              class="mt-3"
              message="解码只说明结构可读，不代表 token 可信 —— 必须验签通过才算有效"
              show-icon
              type="info"
            />
          </Card>

          <Card size="small" title="Decoded Header / Payload">
            <div class="mb-1 text-sm text-gray-500">Header</div>
            <TextArea
              :rows="6"
              :value="parsed?.header"
              placeholder="{}"
              readonly
            />
            <div class="mb-1 mt-3 text-sm text-gray-500">Payload</div>
            <TextArea
              :rows="8"
              :value="parsed?.payload"
              placeholder="{}"
              readonly
            />
            <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-gray-500">
              <Tag v-if="parsed?.alg">alg = {{ parsed.alg }}</Tag>
              <Tag v-if="parsed?.typ">typ = {{ parsed.typ }}</Tag>
              <Tag v-if="parsed?.kid">kid = {{ parsed.kid }}</Tag>
              <Tag>{{ parsed?.signature.length ?? 0 }} 字符签名</Tag>
            </div>
          </Card>

          <Card size="small" title="签名验证（需要密钥）">
            <div class="mb-2 flex flex-wrap items-center gap-2">
              <span class="text-sm text-gray-500">密钥类型</span>
              <Radio.Group v-model:value="keyFormat" button-style="solid">
                <Radio.Button
                  v-for="f in keyFormatOptions"
                  :key="f.value"
                  :value="f.value"
                >
                  {{ f.label }}
                </Radio.Button>
              </Radio.Group>
            </div>
            <div v-if="keyFormat === 'SECRET'" class="mb-2 flex items-center gap-2">
              <span class="text-sm text-gray-500">密钥编码</span>
              <Select
                v-model:value="secretEncoding"
                :options="secretEncodingOptions"
                class="w-32"
              />
            </div>
            <TextArea
              v-model:value="secret"
              :rows="keyRows"
              allow-clear
              :placeholder="keyPlaceholder"
            />
            <div class="mt-1 text-xs text-gray-400">{{ keyHint }}</div>
            <div class="mt-3">
              <Space>
                <Button
                  :loading="verifying"
                  type="primary"
                  @click="onVerify"
                >
                  验证签名
                </Button>
                <Button :disabled="!verifyResult" @click="verifyResult = undefined">
                  清除结果
                </Button>
              </Space>
            </div>
            <Alert
              v-if="verifyResult"
              class="mt-3"
              :description="verifyResult.reason"
              :message="verifyResult.verified ? '签名验证通过' : '签名未通过'"
              :type="verifyResult.verified ? 'success' : 'error'"
              show-icon
            />
            <div
              v-if="verifyResult"
              class="mt-2 flex flex-wrap items-center gap-2 text-xs text-gray-500"
            >
              <Tag>算法 {{ verifyResult.algorithm || '-' }}</Tag>
              <Tag>族 {{ verifyResult.family || '-' }}</Tag>
              <Tag>密钥 {{ verifyResult.keyType || '-' }}</Tag>
              <Tag v-if="verifyResult.kid">kid {{ verifyResult.kid }}</Tag>
            </div>
          </Card>
        </div>

        <Card class="mt-4" size="small" title="时间声明">
          <Alert
            v-if="parsed?.timeMessage"
            :message="parsed.timeMessage"
            :type="parsed.timeLevel ?? 'info'"
            show-icon
          />
          <div v-else class="text-sm text-gray-400">
            粘贴 token 后显示 exp / nbf 的判定结果（按本机时间计算）
          </div>
        </Card>

        <Card class="mt-4" size="small" title="Claims Breakdown（声明说明）">
          <Table
            v-if="parsed?.claims.length"
            :columns="claimColumns"
            :data-source="parsed.claims"
            :pagination="false"
            row-key="name"
            size="small"
          />
          <div v-else class="text-sm text-gray-400">无可展示的声明</div>
        </Card>
      </Tabs.TabPane>

      <Tabs.TabPane key="encode" tab="生成签名">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card size="small" title="Header / Payload">
            <div class="mb-1 text-sm text-gray-500">签名算法</div>
            <Select
              v-model:value="algorithm"
              :options="algorithmOptions"
              class="w-full"
            />
            <div class="mt-1 text-xs text-gray-400">{{ algorithmNote }}</div>

            <div class="mb-1 mt-3 text-sm text-gray-500">
              Header 附加字段（可选，JSON；alg 由服务端写入、不可覆盖）
            </div>
            <TextArea
              v-model:value="headerExtra"
              :rows="3"
              allow-clear
              placeholder='{"kid":"my-key-id"}'
            />

            <div class="mb-1 mt-3 text-sm text-gray-500">Payload（JSON 对象）</div>
            <TextArea
              v-model:value="signPayload"
              :rows="10"
              :placeholder="DEFAULT_PAYLOAD_PLACEHOLDER"
            />
            <div class="mt-2">
              <Space>
                <Button @click="fillTimes">写入当前 iat / exp(1h)</Button>
                <Button @click="signPayload = DEFAULT_PAYLOAD">重置示例</Button>
              </Space>
            </div>
          </Card>

          <Card size="small" title="签名密钥">
            <div class="mb-2 flex flex-wrap items-center gap-2">
              <span class="text-sm text-gray-500">密钥类型</span>
              <Radio.Group v-model:value="keyFormat" button-style="solid">
                <Radio.Button
                  v-for="f in keyFormatOptions"
                  :key="f.value"
                  :value="f.value"
                >
                  {{ f.label }}
                </Radio.Button>
              </Radio.Group>
            </div>
            <div v-if="keyFormat === 'SECRET'" class="mb-2 flex items-center gap-2">
              <span class="text-sm text-gray-500">密钥编码</span>
              <Select
                v-model:value="secretEncoding"
                :options="secretEncodingOptions"
                class="w-32"
              />
            </div>
            <TextArea
              v-model:value="secret"
              :rows="keyRows"
              allow-clear
              :placeholder="keyPlaceholder"
            />
            <div class="mt-1 text-xs text-gray-400">{{ keyHint }}</div>
            <Alert
              class="mt-3"
              message="签发需要私钥"
              description="HS* 用对称密钥；RS / PS / ES / EdDSA 必须提供 PEM 私钥（PKCS#8）或带 d 的 JWK。本工具不提供 alg=none 签发。"
              show-icon
              type="warning"
            />
            <div class="mt-3">
              <Button :loading="signing" type="primary" @click="onSign">
                生成 Token
              </Button>
            </div>
            <Alert
              v-if="signResult"
              class="mt-3"
              message="已生成签名 JWT"
              show-icon
              type="success"
            />
          </Card>

          <Card size="small" title="结果">
            <TextArea
              :rows="10"
              :value="signResult?.token"
              placeholder="点击「生成 Token」后在此显示"
              readonly
            />
            <div class="mt-2 text-xs text-gray-400">
              服务端写入的 Header：{{ signResult?.header ?? '-' }}
            </div>
            <div class="mt-3">
              <Space wrap>
                <Button
                  :disabled="!signResult"
                  @click="copyText(signResult?.token ?? '')"
                >
                  复制 Token
                </Button>
                <Button :disabled="!signResult" @click="useSignedToken">
                  在解码页打开
                </Button>
              </Space>
            </div>
          </Card>
        </div>
      </Tabs.TabPane>
    </Tabs>
  </Page>
</template>
