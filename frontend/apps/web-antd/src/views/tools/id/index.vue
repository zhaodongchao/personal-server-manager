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
  Select,
  Switch,
  Table,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';

import { decodeIdApi, generateIdsApi, getIdOptionsApi } from '#/api';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsId' });

const TextArea = Input.TextArea;

/** 位段配色：让「时间 / 机器 / 序列 / 随机」这类语义一眼可辨 */
const ROLE_COLORS: Record<string, string> = {
  SIGN: '#94a3b8',
  TIME: '#2563eb',
  MACHINE: '#f59e0b',
  SEQ: '#16a34a',
  RANDOM: '#a855f7',
  VERSION: '#0ea5e9',
  VARIANT: '#14b8a6',
  COUNTER: '#ef4444',
};

function roleColor(role: string) {
  return ROLE_COLORS[role] ?? '#64748b';
}

// ---------------- 方案清单（全部由服务端下发） ----------------

const opts = ref<ToolsApi.IdOptions>();
const tab = ref('generate');
const scheme = ref('SNOWFLAKE');
const count = ref(10);
const params = ref<Record<string, any>>({});
const loading = ref(false);
const result = ref<ToolsApi.IdGenerateResult>();

const schemes = computed(() => opts.value?.schemes ?? []);
const current = computed(() =>
  schemes.value.find((s) => s.value === scheme.value),
);
const groupList = computed(() => {
  const list: { label: string; value: string }[] = [];
  for (const s of schemes.value) {
    if (!list.some((g) => g.value === s.group)) {
      list.push({ label: s.groupLabel, value: s.group });
    }
  }
  return list;
});
const schemeOptions = computed(() =>
  schemes.value.map((s) => ({ label: s.label, value: s.value })),
);

function schemesOf(group: string) {
  return schemes.value.filter((s) => s.group === group);
}

function pick(value: string) {
  scheme.value = value;
}

/** 切换方案时按定义重置参数，避免上一个方案的机器号残留在下一个方案里 */
function applyDefaults() {
  params.value = {};
  for (const p of current.value?.params ?? []) {
    params.value[p.name] = p.def ?? '';
  }
}

watch(scheme, () => {
  applyDefaults();
  result.value = undefined;
});

// ---------------- 生成 ----------------

async function doGenerate() {
  loading.value = true;
  try {
    result.value = await generateIdsApi({
      params: { ...params.value },
      scheme: scheme.value,
      count: count.value,
    });
  } catch {
    // 服务端业务错误已由请求拦截器统一 toast，这里不重复提示
  } finally {
    loading.value = false;
  }
}

const columns = [
  { dataIndex: 'index', key: 'index', title: '#', width: 60 },
  { dataIndex: 'value', key: 'value', title: 'ID', ellipsis: true },
  { dataIndex: 'time', key: 'time', title: '解码时间', width: 185 },
  { dataIndex: 'extra', key: 'extra', title: '说明' },
];

function copyOne(value: string) {
  void copyText(value);
}

function copyAll() {
  if (!result.value) {
    return;
  }
  void copyText(result.value.ids.map((i) => i.value).join('\n'));
}

function download(fileName: string, content: string, type: string) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}

function csvCell(v: unknown) {
  const s = v === null || v === undefined ? '' : String(v);
  return `"${s.replaceAll('"', '""')}"`;
}

function exportCsv() {
  const r = result.value;
  if (!r) {
    return;
  }
  const lines = [
    ['序号', 'ID', '十六进制', '解码时间', '说明'].map(csvCell).join(','),
  ];
  for (const it of r.ids) {
    lines.push(
      [it.index, it.value, it.hex ?? '', it.time ?? '', it.extra ?? '']
        .map(csvCell)
        .join(','),
    );
  }
  download(
    `ids-${r.scheme.toLowerCase()}-${Date.now()}.csv`,
    `\uFEFF${lines.join('\n')}`,
    'text/csv;charset=utf-8',
  );
  message.success('已导出 CSV');
}

function exportJson() {
  const r = result.value;
  if (!r) {
    return;
  }
  download(
    `ids-${r.scheme.toLowerCase()}-${Date.now()}.json`,
    JSON.stringify(r.ids, null, 2),
    'application/json;charset=utf-8',
  );
  message.success('已导出 JSON');
}

// ---------------- 反解 ----------------

const decodeScheme = ref('SNOWFLAKE');
const decodeValue = ref('');
const decodeEpoch = ref<number | undefined>();
const decodeLoading = ref(false);
const decodeResult = ref<ToolsApi.IdDecodeResult>();

const decodeCurrent = computed(() =>
  schemes.value.find((s) => s.value === decodeScheme.value),
);
/** 雪花类的时间戳是「相对基准的偏移」，不知道基准就反解不出真实时间 */
const decodeNeedEpoch = computed(
  () => decodeCurrent.value?.group === 'SNOWFLAKE',
);

function fillDecodeSample() {
  decodeValue.value = decodeCurrent.value?.sample ?? '';
}

async function doDecode() {
  if (!decodeValue.value.trim()) {
    message.warning('请先粘贴一个 ID');
    return;
  }
  decodeLoading.value = true;
  try {
    decodeResult.value = await decodeIdApi({
      epoch: decodeNeedEpoch.value ? (decodeEpoch.value ?? null) : null,
      scheme: decodeScheme.value,
      value: decodeValue.value.trim(),
    });
  } catch {
    // 服务端业务错误已由请求拦截器统一 toast，这里不重复提示
  } finally {
    decodeLoading.value = false;
  }
}

// ---------------- 初始化 ----------------

onMounted(async () => {
  try {
    opts.value = await getIdOptionsApi();
    if (!schemes.value.some((s) => s.value === scheme.value)) {
      scheme.value = schemes.value[0]?.value ?? '';
    }
    if (!schemes.value.some((s) => s.value === decodeScheme.value)) {
      decodeScheme.value = schemes.value[0]?.value ?? '';
    }
    applyDefaults();
  } catch {
    message.error('方案清单加载失败，请刷新页面重试');
  }
});
</script>

<template>
  <Page
    title="ID 生成器"
    description="9 种常用 ID 方案的位分配对照、批量生成与反解 —— 自增 / 序列 / UUIDv1·v4·v7 / ObjectId / Snowflake / UidGenerator / Sonyflake"
  >
    <Alert class="mb-4" show-icon type="warning">
      <template #message>
        第一类（自增计数器 / 序列）是 <b>参数化模拟</b>：不连接数据库、不创建任何表或序列对象，
        只按你给的起始值、步长、缓存段推演号段并标出空洞的成因；真实取号请用「应用栈 → 数据库」。
        本页生成的 ID 不落库、不参与任何业务，仅供观察与对照。
      </template>
    </Alert>

    <Tabs v-model:active-key="tab">
      <Tabs.TabPane key="generate" tab="生成 ID">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card size="small" title="选择方案">
            <div v-for="g in groupList" :key="g.value" class="mb-4">
              <div class="mb-2 text-xs font-medium text-gray-500">
                {{ g.label }}
              </div>
              <div class="flex flex-col gap-2">
                <div
                  v-for="s in schemesOf(g.value)"
                  :key="s.value"
                  class="cursor-pointer rounded border border-solid px-3 py-2 transition-all"
                  :class="
                    s.value === scheme
                      ? 'border-blue-500 bg-blue-50'
                      : 'border-gray-200 hover:border-blue-300'
                  "
                  @click="pick(s.value)"
                >
                  <div class="flex items-center justify-between gap-2">
                    <span class="text-sm">{{ s.label }}</span>
                    <span class="font-mono text-xs text-gray-400">
                      {{ s.bits || '—' }}
                    </span>
                  </div>
                  <div class="mt-1 text-xs text-gray-400">{{ s.ordered }}</div>
                </div>
              </div>
            </div>
          </Card>

          <div class="lg:col-span-2">
            <Card size="small" title="参数与生成">
              <div v-if="current" class="mb-3 text-xs text-gray-500">
                {{ current.note }}
              </div>

              <div class="flex flex-wrap items-end gap-3">
                <div>
                  <div class="mb-1 text-sm text-gray-500">生成数量</div>
                  <InputNumber
                    v-model:value="count"
                    :max="opts?.maxCount ?? 1000"
                    :min="1"
                    class="w-32"
                  />
                </div>
                <Button :loading="loading" type="primary" @click="doGenerate">
                  {{ count === 1 ? '生成 1 个' : `生成 ${count} 个` }}
                </Button>
                <Button :disabled="!result" @click="copyAll">复制全部</Button>
                <Button :disabled="!result" @click="exportCsv">导出 CSV</Button>
                <Button :disabled="!result" @click="exportJson">导出 JSON</Button>
              </div>

              <div
                v-if="(current?.params ?? []).length > 0"
                class="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2"
              >
                <div v-for="p in current?.params ?? []" :key="p.name">
                  <div class="mb-1 text-sm text-gray-500">
                    {{ p.label }}
                    <span v-if="p.required && !p.def" class="text-red-500">*</span>
                  </div>
                  <Select
                    v-if="p.type === 'select'"
                    v-model:value="params[p.name]"
                    :options="p.options ?? []"
                  />
                  <InputNumber
                    v-else-if="p.type === 'number'"
                    v-model:value="params[p.name]"
                    :max="p.max ?? undefined"
                    :min="p.min ?? undefined"
                    class="w-full"
                  />
                  <Switch
                    v-else-if="p.type === 'switch'"
                    :checked="params[p.name] === 'true' || params[p.name] === true"
                    @change="
                      (v: any) => (params[p.name] = v ? 'true' : 'false')
                    "
                  />
                  <Input v-else v-model:value="params[p.name]" allow-clear />
                  <div v-if="p.help" class="mt-1 text-xs text-gray-400">
                    {{ p.help }}
                  </div>
                </div>
              </div>

              <div
                v-if="current"
                class="mt-4 border-t border-gray-100 pt-3 text-xs text-gray-400"
              >
                <div>
                  生成方：{{ current.generator }}｜形态：{{ current.shape }}｜单调性：{{
                    current.ordered
                  }}
                  <span v-if="current.totalBits > 0">
                    ｜总位数：{{ current.totalBits }}
                  </span>
                </div>
                <div class="mt-1">
                  示例：<span class="font-mono">{{ current.sample }}</span>
                </div>
              </div>
            </Card>

            <Card v-if="result" class="mt-4" size="small" title="位段结构">
              <div
                v-if="result.segments.length > 0"
                class="flex h-8 w-full overflow-hidden rounded"
              >
                <div
                  v-for="(seg, i) in result.segments"
                  :key="i"
                  class="flex items-center justify-center overflow-hidden whitespace-nowrap text-xs text-white"
                  :style="{
                    backgroundColor: roleColor(seg.role),
                    flexGrow: seg.width,
                    flexBasis: 0,
                  }"
                  :title="`${seg.name}（${seg.width} 位）${seg.note ? '：' + seg.note : ''}`"
                >
                  <span v-if="seg.width >= 8">{{ seg.width }}</span>
                </div>
              </div>
              <div class="mt-3 flex flex-wrap gap-x-6 gap-y-2">
                <div
                  v-for="(seg, i) in result.segments"
                  :key="i"
                  class="flex items-center gap-2 text-xs"
                >
                  <span
                    class="inline-block h-3 w-3 rounded-sm"
                    :style="{ backgroundColor: roleColor(seg.role) }"
                  ></span>
                  <span class="text-gray-600">{{ seg.name }}</span>
                  <span class="text-gray-400">{{ seg.width }} 位</span>
                  <span v-if="seg.note" class="text-gray-400">· {{ seg.note }}</span>
                </div>
              </div>
              <div v-if="result.bits" class="mt-3 font-mono text-xs text-gray-400">
                {{ result.bits }} = {{ result.totalBits }} 位
              </div>
            </Card>

            <Card
              v-if="result"
              class="mt-4"
              size="small"
              :title="`生成结果（${result.count} 条，耗时 ${result.elapsedMs} ms）`"
            >
              <Alert
                v-for="(w, i) in result.warnings"
                :key="`w${i}`"
                class="mb-2"
                show-icon
                type="warning"
              >
                <template #message>{{ w }}</template>
              </Alert>
              <Alert
                v-for="(n, i) in result.notes"
                :key="`n${i}`"
                class="mb-2"
                show-icon
                type="info"
              >
                <template #message>{{ n }}</template>
              </Alert>

              <Table
                :columns="columns"
                :data-source="result.ids"
                :pagination="false"
                :scroll="{ y: 420 }"
                row-key="index"
                size="small"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'value'">
                    <span class="font-mono">{{ record.value }}</span>
                    <Button
                      class="ml-2"
                      size="small"
                      type="link"
                      @click="copyOne(record.value)"
                    >
                      复制
                    </Button>
                  </template>
                </template>
                <template #expandedRowRender="{ record }">
                  <div
                    v-if="record.segValues"
                    class="grid grid-cols-1 gap-1 md:grid-cols-2"
                  >
                    <div
                      v-for="(seg, i) in result?.segments ?? []"
                      :key="i"
                      class="flex items-center gap-2 text-xs"
                    >
                      <span
                        class="inline-block h-3 w-3 shrink-0 rounded-sm"
                        :style="{ backgroundColor: roleColor(seg.role) }"
                      ></span>
                      <span class="w-40 shrink-0 text-gray-500">
                        {{ seg.name }}（{{ seg.width }} 位）
                      </span>
                      <span class="font-mono break-all">
                        {{ record.segValues[i] ?? '—' }}
                      </span>
                    </div>
                  </div>
                  <div v-else class="text-xs text-gray-400">
                    本次生成条数超过上限，未返回逐条位段拆解
                  </div>
                </template>
              </Table>
            </Card>
          </div>
        </div>
      </Tabs.TabPane>

      <Tabs.TabPane key="decode" tab="反解 ID">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card size="small" title="粘贴一个 ID">
            <div class="mb-2 text-sm text-gray-500">按哪个方案反解</div>
            <Select v-model:value="decodeScheme" :options="schemeOptions" />
            <div v-if="decodeNeedEpoch" class="mt-3">
              <div class="mb-1 text-sm text-gray-500">时间基准 epoch（毫秒）</div>
              <InputNumber
                v-model:value="decodeEpoch"
                class="w-full"
                placeholder="留空则用本页默认基准"
              />
              <div class="mt-1 text-xs text-gray-400">
                雪花类的时间戳存的是「相对基准的偏移」，基准不对，反解出的时间也不对
              </div>
            </div>
            <div class="mt-3">
              <div class="mb-1 text-sm text-gray-500">ID</div>
              <TextArea
                v-model:value="decodeValue"
                :rows="4"
                allow-clear
                placeholder="例如 1541815603606036480 / 018f1c1e-9a3b-7c2d-8e4f-0123456789ab / 507f1f77bcf86cd799439011"
              />
            </div>
            <div class="mt-3">
              <Button :loading="decodeLoading" type="primary" @click="doDecode">
                反解
              </Button>
              <Button class="ml-2" @click="fillDecodeSample">填入示例</Button>
              <Button class="ml-2" @click="decodeValue = ''">清空</Button>
            </div>
          </Card>

          <div class="lg:col-span-2">
            <Card v-if="!decodeResult" size="small" title="反解结果">
              <div class="py-6 text-center text-sm text-gray-400">
                粘贴一个 ID 后点击「反解」，会拆出每一段的取值并还原生成时间
              </div>
            </Card>

            <template v-else>
              <Card size="small" title="反解结果">
                <div class="flex flex-wrap items-center gap-3">
                  <Tag :color="decodeResult.valid ? 'green' : 'red'">
                    {{ decodeResult.valid ? '与方案匹配' : '与方案不匹配' }}
                  </Tag>
                  <span class="text-sm text-gray-500">{{ decodeResult.label }}</span>
                </div>
                <Alert
                  class="mt-3"
                  :message="decodeResult.reason"
                  :type="decodeResult.valid ? 'success' : 'error'"
                  show-icon
                />
                <div class="mt-3 text-sm">
                  <span class="text-gray-500">规范化 ID：</span>
                  <span class="font-mono break-all">{{ decodeResult.value }}</span>
                  <Button
                    class="ml-2"
                    size="small"
                    type="link"
                    @click="copyOne(decodeResult.value)"
                  >
                    复制
                  </Button>
                </div>
                <div v-if="decodeResult.time" class="mt-2 text-sm">
                  <span class="text-gray-500">还原生成时间：</span>
                  <span class="font-mono">{{ decodeResult.time }}</span>
                </div>
                <div v-if="decodeResult.hex" class="mt-2 text-sm">
                  <span class="text-gray-500">十六进制：</span>
                  <span class="font-mono">{{ decodeResult.hex }}</span>
                </div>
                <div v-if="decodeResult.facts.length > 0" class="mt-3 text-xs text-gray-500">
                  <div v-for="(f, i) in decodeResult.facts" :key="i">· {{ f }}</div>
                </div>
              </Card>

              <Card
                v-if="decodeResult.segments.length > 0"
                class="mt-4"
                size="small"
                title="位段拆解"
              >
                <div class="flex h-8 w-full overflow-hidden rounded">
                  <div
                    v-for="(seg, i) in decodeResult.segments"
                    :key="i"
                    class="flex items-center justify-center overflow-hidden whitespace-nowrap text-xs text-white"
                    :style="{
                      backgroundColor: roleColor(seg.role),
                      flexGrow: seg.width,
                      flexBasis: 0,
                    }"
                    :title="`${seg.name}（${seg.width} 位）`"
                  >
                    <span v-if="seg.width >= 8">{{ seg.width }}</span>
                  </div>
                </div>
                <div class="mt-3 grid grid-cols-1 gap-1 md:grid-cols-2">
                  <div
                    v-for="(seg, i) in decodeResult.segments"
                    :key="i"
                    class="flex items-center gap-2 text-xs"
                  >
                    <span
                      class="inline-block h-3 w-3 shrink-0 rounded-sm"
                      :style="{ backgroundColor: roleColor(seg.role) }"
                    ></span>
                    <span class="w-40 shrink-0 text-gray-500">
                      {{ seg.name }}（{{ seg.width }} 位）
                    </span>
                    <span class="font-mono break-all">
                      {{ (decodeResult.segValues ?? [])[i] ?? '—' }}
                    </span>
                  </div>
                </div>
              </Card>
            </template>
          </div>
        </div>
      </Tabs.TabPane>
    </Tabs>
  </Page>
</template>
