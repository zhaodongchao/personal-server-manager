<script lang="ts" setup>
import type { ToolsApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  Button,
  Card,
  Input,
  Radio,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';

import {
  codecApi,
  digestApi,
  getCryptoOptionsApi,
  hmacApi,
  symmetricApi,
} from '#/api';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsStringCrypto' });

const TextArea = Input.TextArea;

/** 算法清单由服务端下发，前端不硬编码算法名 */
const opts = ref<ToolsApi.CryptoOptions>();
const loading = ref(false);
const activeKey = ref('digest');
const output = ref('');

function toOptions(list?: ToolsApi.Option[]) {
  return (list ?? []).map((o) => ({ label: o.label, value: o.value }));
}

// ---------------- 摘要 ----------------
const digestText = ref('');
const digestAlg = ref('SHA256');
const digestUpper = ref(false);

// ---------------- HMAC ----------------
const hmacText = ref('');
const hmacKey = ref('');
const hmacKeyEnc = ref('TEXT');
const hmacAlg = ref('SHA256');
const hmacUpper = ref(false);

// ---------------- 编码 ----------------
const codecText = ref('');
const codecAlg = ref('BASE64');
const codecOp = ref('ENCODE');

// ---------------- 对称加解密 ----------------
const symText = ref('');
const symAlg = ref('AES');
const symKey = ref('');
const symKeyEnc = ref('TEXT');
const symMode = ref('CBC');
const symIv = ref('');
const symIvEnc = ref('TEXT');
const symPadding = ref('PKCS5Padding');
const symOp = ref('ENCRYPT');
const symInEnc = ref('BASE64');
const symOutEnc = ref('BASE64');

const currentCipher = computed(() =>
  (opts.value?.ciphers ?? []).find((c) => c.value === symAlg.value),
);
/** 流算法（RC4）无分组模式 */
const isStream = computed(() => (currentCipher.value?.modes ?? []).length === 0);
const needIv = computed(() => !isStream.value && symMode.value === 'CBC');

const digestOptions = computed(() => toOptions(opts.value?.digests));
const hmacOptions = computed(() => toOptions(opts.value?.hmacs));
const encodingOptions = computed(() => toOptions(opts.value?.encodings));
const keyEncodingOptions = computed(() => toOptions(opts.value?.keyEncodings));
const cipherEncodingOptions = computed(() =>
  toOptions(opts.value?.cipherEncodings),
);
const cipherOptions = computed(() =>
  (opts.value?.ciphers ?? []).map((c) => ({ label: c.label, value: c.value })),
);
const modeOptions = computed(() =>
  (currentCipher.value?.modes ?? []).map((m) => ({ label: m, value: m })),
);

onMounted(async () => {
  try {
    opts.value = await getCryptoOptionsApi();
  } catch {
    message.error('能力清单加载失败，请刷新页面重试');
  }
});

/** 统一执行：失败时清空输出（错误提示由 request 拦截器统一弹出） */
async function run(task: () => Promise<string>) {
  loading.value = true;
  try {
    output.value = await task();
  } catch {
    output.value = '';
  } finally {
    loading.value = false;
  }
}

function onDigest() {
  run(() =>
    digestApi({
      algorithm: digestAlg.value,
      text: digestText.value,
      upper: digestUpper.value,
    }),
  );
}

function onHmac() {
  run(() =>
    hmacApi({
      algorithm: hmacAlg.value,
      key: hmacKey.value,
      keyEncoding: hmacKeyEnc.value,
      text: hmacText.value,
      upper: hmacUpper.value,
    }),
  );
}

function onCodec() {
  run(() =>
    codecApi({
      algorithm: codecAlg.value,
      op: codecOp.value,
      text: codecText.value,
    }),
  );
}

function onSymmetric() {
  run(() =>
    symmetricApi({
      algorithm: symAlg.value,
      inputEncoding: symInEnc.value,
      iv: symIv.value,
      ivEncoding: symIvEnc.value,
      key: symKey.value,
      keyEncoding: symKeyEnc.value,
      mode: symMode.value,
      op: symOp.value,
      outputEncoding: symOutEnc.value,
      padding: symPadding.value,
      text: symText.value,
    }),
  );
}
</script>

<template>
  <Page
    title="字符串加密解密"
    description="摘要 / HMAC / Base64·Hex 编解码 / 对称加解密 — 算法能力由后端提供，入参不落审计表"
  >
    <Tabs v-model:active-key="activeKey">
      <Tabs.TabPane key="digest" tab="摘要">
        <Card>
          <div class="mb-2 text-sm text-gray-500">待处理文本</div>
          <TextArea v-model:value="digestText" :rows="5" allow-clear />
          <div class="mt-3 flex flex-wrap items-center gap-3">
            <span class="text-sm">算法</span>
            <Select
              v-model:value="digestAlg"
              :options="digestOptions"
              class="w-64"
            />
            <span class="text-sm">大写输出</span>
            <Switch v-model:checked="digestUpper" />
            <Button :loading="loading" type="primary" @click="onDigest">
              计算
            </Button>
          </div>
        </Card>
      </Tabs.TabPane>

      <Tabs.TabPane key="hmac" tab="HMAC">
        <Card>
          <div class="mb-2 text-sm text-gray-500">待处理文本</div>
          <TextArea v-model:value="hmacText" :rows="4" allow-clear />
          <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <div class="mb-1 text-sm text-gray-500">密钥</div>
              <Input v-model:value="hmacKey" allow-clear placeholder="密钥" />
            </div>
            <div>
              <div class="mb-1 text-sm text-gray-500">密钥录入方式</div>
              <Select
                v-model:value="hmacKeyEnc"
                :options="keyEncodingOptions"
                class="w-full"
              />
            </div>
            <div>
              <div class="mb-1 text-sm text-gray-500">算法</div>
              <Select
                v-model:value="hmacAlg"
                :options="hmacOptions"
                class="w-full"
              />
            </div>
            <div class="flex items-end gap-2">
              <span class="text-sm">大写输出</span>
              <Switch v-model:checked="hmacUpper" />
            </div>
          </div>
          <div class="mt-3">
            <Button :loading="loading" type="primary" @click="onHmac">
              计算
            </Button>
          </div>
        </Card>
      </Tabs.TabPane>

      <Tabs.TabPane key="codec" tab="Base64 / Hex">
        <Card>
          <div class="mb-2 text-sm text-gray-500">待处理文本</div>
          <TextArea v-model:value="codecText" :rows="5" allow-clear />
          <div class="mt-3 flex flex-wrap items-center gap-3">
            <Radio.Group v-model:value="codecOp">
              <Radio.Button value="ENCODE">编码</Radio.Button>
              <Radio.Button value="DECODE">解码</Radio.Button>
            </Radio.Group>
            <Select
              v-model:value="codecAlg"
              :options="encodingOptions"
              class="w-56"
            />
            <Button :loading="loading" type="primary" @click="onCodec">
              {{ codecOp === 'ENCODE' ? '编码' : '解码' }}
            </Button>
          </div>
        </Card>
      </Tabs.TabPane>

      <Tabs.TabPane key="symmetric" tab="对称加解密">
        <Card>
          <div class="mb-2 text-sm text-gray-500">
            {{ symOp === 'ENCRYPT' ? '明文' : '密文（Base64 / Hex）' }}
          </div>
          <TextArea v-model:value="symText" :rows="4" allow-clear />
          <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <div class="mb-1 text-sm text-gray-500">算法</div>
              <Select
                v-model:value="symAlg"
                :options="cipherOptions"
                class="w-full"
              />
              <div v-if="currentCipher" class="mt-1 text-xs text-gray-400">
                {{ currentCipher.note }}
              </div>
            </div>
            <div v-if="!isStream">
              <div class="mb-1 text-sm text-gray-500">分组模式</div>
              <Select
                v-model:value="symMode"
                :options="modeOptions"
                class="w-full"
              />
            </div>
            <div>
              <div class="mb-1 text-sm text-gray-500">密钥</div>
              <Input v-model:value="symKey" allow-clear />
            </div>
            <div>
              <div class="mb-1 text-sm text-gray-500">密钥录入方式</div>
              <Select
                v-model:value="symKeyEnc"
                :options="keyEncodingOptions"
                class="w-full"
              />
            </div>
            <div v-if="needIv">
              <div class="mb-1 text-sm text-gray-500">
                IV（{{ currentCipher?.ivLength }} 字节）
              </div>
              <Input v-model:value="symIv" allow-clear />
            </div>
            <div v-if="needIv">
              <div class="mb-1 text-sm text-gray-500">IV 录入方式</div>
              <Select
                v-model:value="symIvEnc"
                :options="keyEncodingOptions"
                class="w-full"
              />
            </div>
            <div v-if="!isStream">
              <div class="mb-1 text-sm text-gray-500">填充</div>
              <Select
                v-model:value="symPadding"
                :options="[
                  { label: 'PKCS5Padding', value: 'PKCS5Padding' },
                  { label: 'NoPadding', value: 'NoPadding' },
                ]"
                class="w-full"
              />
            </div>
            <div>
              <div class="mb-1 text-sm text-gray-500">
                {{ symOp === 'ENCRYPT' ? '密文输出编码' : '密文输入编码' }}
              </div>
              <Select
                v-if="symOp === 'ENCRYPT'"
                v-model:value="symOutEnc"
                :options="cipherEncodingOptions"
                class="w-full"
              />
              <Select
                v-else
                v-model:value="symInEnc"
                :options="cipherEncodingOptions"
                class="w-full"
              />
            </div>
          </div>
          <div class="mt-3 flex flex-wrap items-center gap-3">
            <Radio.Group v-model:value="symOp">
              <Radio.Button value="ENCRYPT">加密</Radio.Button>
              <Radio.Button value="DECRYPT">解密</Radio.Button>
            </Radio.Group>
            <Button :loading="loading" type="primary" @click="onSymmetric">
              {{ symOp === 'ENCRYPT' ? '加密' : '解密' }}
            </Button>
            <Tag v-if="isStream" color="orange">流算法：无分组模式与 IV</Tag>
          </div>
        </Card>
      </Tabs.TabPane>
    </Tabs>

    <Card class="mt-4" title="结果">
      <TextArea :value="output" :rows="4" readonly />
      <div class="mt-3">
        <Space>
          <Button :disabled="!output" @click="copyText(output)">复制结果</Button>
          <Button
            :disabled="!output"
            @click="
              () => {
                output = '';
              }
            "
          >
            清空
          </Button>
        </Space>
      </div>
    </Card>
  </Page>
</template>
