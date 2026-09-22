<script lang="ts" setup>
import type { ToolsApi } from '#/api';

import { computed, onMounted, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Tag,
  message,
} from 'ant-design-vue';

import { getObfuscateOptionsApi, obfuscateApi } from '#/api';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsObfuscate' });

const TextArea = Input.TextArea;

const methods = ref<ToolsApi.ObfuscateMethod[]>([]);
const method = ref('XOR');
const op = ref<'DEOBFUSCATE' | 'OBFUSCATE'>('OBFUSCATE');
const text = ref('');
const output = ref('');
const loading = ref(false);

/** 各方式参数差异大，统一用 map 承载；类型放宽以兼容 InputNumber 的 number 值 */
const params = ref<Record<string, any>>({});

const current = computed(() =>
  methods.value.find((m) => m.value === method.value),
);
const methodOptions = computed(() =>
  methods.value.map((m) => ({ label: m.label, value: m.value })),
);

/** 切换方式时按服务端默认值重置参数，避免上一方式的参数残留 */
watch(method, (val) => {
  const next: Record<string, any> = {};
  const found = methods.value.find((m) => m.value === val);
  for (const f of found?.fields ?? []) {
    if (f.def !== null && f.def !== undefined) {
      next[f.name] = f.def;
    }
  }
  params.value = next;
});

onMounted(async () => {
  try {
    const data = await getObfuscateOptionsApi();
    methods.value = data.methods ?? [];
    if (methods.value.length > 0) {
      method.value = methods.value[0]!.value;
    }
  } catch {
    message.error('混淆方式加载失败，请刷新页面重试');
  }
});

async function run() {
  if (!text.value) {
    message.warning('请先输入内容');
    return;
  }
  const body: ToolsApi.ObfuscateBody = {
    method: method.value,
    op: op.value,
    params: Object.fromEntries(
      Object.entries(params.value).map(([k, v]) => [k, String(v ?? '')]),
    ),
    text: text.value,
  };
  loading.value = true;
  try {
    output.value = await obfuscateApi(body);
  } catch {
    output.value = '';
  } finally {
    loading.value = false;
  }
}

function useResult() {
  if (!output.value) return;
  text.value = output.value;
  op.value = op.value === 'OBFUSCATE' ? 'DEOBFUSCATE' : 'OBFUSCATE';
}
</script>

<template>
  <Page
    title="混淆加密解密"
    description="可逆混淆：XOR / 凯撒 / 倒序 / Base64 嵌套 / 零宽隐写 — 只防偷看，不防破解"
  >
    <Alert class="mb-4" show-icon type="warning">
      <template #message>
        混淆不是加密：以下方式全部可逆且<b>不具备任何安全性</b>，只能让明文不直接可读，
        不能抵御有意的破解。传输或存储敏感数据请使用「字符串加密解密」的对称加密。
      </template>
    </Alert>

    <Card>
      <div class="flex flex-wrap items-center gap-3">
        <span class="text-sm text-gray-500">混淆方式</span>
        <Select
          v-model:value="method"
          :options="methodOptions"
          class="w-64"
        />
        <Tag v-if="current?.symmetric" color="blue">对称：正反同一操作</Tag>
        <Radio.Group v-model:value="op">
          <Radio.Button value="OBFUSCATE">混淆</Radio.Button>
          <Radio.Button value="DEOBFUSCATE">反混淆</Radio.Button>
        </Radio.Group>
      </div>
      <div v-if="current?.desc" class="mt-2 text-xs text-gray-400">
        {{ current.desc }}
      </div>

      <!-- 按服务端下发的字段定义渲染，后端新增参数前端自动出现 -->
      <div
        v-if="(current?.fields ?? []).length > 0"
        class="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2"
      >
        <div v-for="f in current?.fields ?? []" :key="f.name">
          <div class="mb-1 text-sm text-gray-500">
            {{ f.label }}
            <span v-if="f.required" class="text-red-500">*</span>
          </div>
          <Input
            v-if="f.type === 'text'"
            v-model:value="params[f.name]"
            :placeholder="f.help || ''"
            allow-clear
          />
          <InputNumber
            v-else-if="f.type === 'number'"
            v-model:value="params[f.name]"
            :max="f.max ?? undefined"
            :min="f.min ?? undefined"
            class="w-full"
          />
          <TextArea
            v-else
            v-model:value="params[f.name]"
            :rows="3"
            :placeholder="f.help || ''"
          />
          <div v-if="f.help" class="mt-1 text-xs text-gray-400">
            {{ f.help }}
          </div>
        </div>
      </div>

      <div class="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
        <div>
          <div class="mb-2 text-sm text-gray-500">输入</div>
          <TextArea v-model:value="text" :rows="6" allow-clear />
        </div>
        <div>
          <div class="mb-2 text-sm text-gray-500">输出</div>
          <TextArea :value="output" :rows="6" readonly />
        </div>
      </div>

      <div class="mt-3">
        <Space>
          <Button :loading="loading" type="primary" @click="run">
            {{ op === 'OBFUSCATE' ? '混淆' : '反混淆' }}
          </Button>
          <Button :disabled="!output" @click="copyText(output)">
            复制结果
          </Button>
          <Button :disabled="!output" @click="useResult">
            结果回填并反向
          </Button>
        </Space>
      </div>
    </Card>
  </Page>
</template>
