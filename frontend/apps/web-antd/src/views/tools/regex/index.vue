<script lang="ts" setup>
import type { ToolsApi } from '#/api';

import { computed, onMounted, reactive, ref, watch } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';

import {
  createRegexTemplateApi,
  deleteRegexTemplateApi,
  generateRegexApi,
  getRegexOptionsApi,
  getRegexTemplateListApi,
  getRegexTemplatePageApi,
  testRegexApi,
  updateRegexTemplateApi,
} from '#/api';
import { useVbenForm } from '#/adapter/form';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsRegex' });

const TextArea = Input.TextArea;

const { hasAccessByCodes } = useAccess();

const CATEGORIES = ['校验', '提取', '替换', '日志', '其他'];
const TOKEN_COLORS: Record<string, string> = {
  alternation: 'red',
  anchor: 'orange',
  charClass: 'blue',
  dot: 'cyan',
  group: 'green',
  groupEnd: 'green',
  literal: 'default',
  quantifier: 'purple',
};

// ---- options ----
const opts = ref<ToolsApi.RegexOptions>();
const tab = ref('generate');

// ---- 生成 Tab ----
const scenario = ref('');
const params = ref<Record<string, any>>({});
const genFlags = ref<string[]>([]);
const generating = ref(false);
const genResult = ref<ToolsApi.RegexGenerateResult>();
const currentScenario = computed(() =>
  (opts.value?.scenarios ?? []).find((s) => s.value === scenario.value),
);
const scenarioOptions = computed(() =>
  (opts.value?.scenarios ?? []).map((s) => ({
    label: s.label,
    value: s.value,
  })),
);
const flagOptions = computed(() =>
  (opts.value?.flags ?? []).map((f) => ({
    label: `${f.label}（${f.value}）`,
    value: f.value,
  })),
);

const canGenerate = computed(() => {
  for (const f of currentScenario.value?.fields ?? []) {
    if (f.required && !String(params.value[f.name] ?? '').trim()) {
      return false;
    }
  }
  return true;
});

/**
 * 按服务端下发的默认值填充参数。
 *
 * 与二维码/混淆页同一个坑：watch 只在 scenario 变化时触发，首屏初值与
 * 清单首项相同时不会触发，必填项就会是空的，加载完成后必须主动调一次。
 * switch 字段的默认值是 "1"/"0" 字符串，这里转成布尔，否则 Switch 会把
 * "0" 当真值全亮。
 */
function applyDefaults() {
  const next: Record<string, any> = {};
  for (const f of currentScenario.value?.fields ?? []) {
    if (f.def === null || f.def === undefined) continue;
    next[f.name] = f.type === 'switch' ? f.def === '1' : f.def;
  }
  params.value = next;
  genResult.value = undefined;
}

watch(scenario, () => applyDefaults());

onMounted(async () => {
  try {
    opts.value = await getRegexOptionsApi();
    const first = opts.value.scenarios?.[0]?.value;
    if (first) {
      scenario.value = first;
    }
    applyDefaults();
    await loadTemplateList();
    await loadTemplatePage();
  } catch {
    message.error('正则工具参数加载失败，请刷新页面重试');
  }
});

/** 参数统一字符串化提交（switch→1/0，number→十进制字符串），与后端 Map<String,String> 对齐 */
function buildSubmitParams(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of currentScenario.value?.fields ?? []) {
    const v = params.value[f.name];
    if (v === undefined || v === null || v === '') continue;
    out[f.name] = typeof v === 'boolean' ? (v ? '1' : '0') : String(v);
  }
  return out;
}

async function onGenerate() {
  if (!canGenerate.value) {
    message.warning('请先填写必填参数');
    return;
  }
  generating.value = true;
  try {
    genResult.value = await generateRegexApi({
      flags: genFlags.value.join(''),
      params: buildSubmitParams(),
      scenario: scenario.value,
    });
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    generating.value = false;
  }
}

/** 把生成的示例文本带去「测试 · 解析」Tab 验证 */
function fillTest(sample: string) {
  testText.value = sample;
  tab.value = 'test';
}

// ---- 测试 · 解析 Tab ----
const testPattern = ref('');
const testFlags = ref<string[]>([]);
const testText = ref('');
const replacement = ref('');
const testing = ref(false);
const testResult = ref<ToolsApi.RegexTestResult>();
/** 实际参与匹配的文本（结果与输入解耦，编辑输入不清空已渲染的高亮） */
const testedText = ref('');

// 模板联动
const templateList = ref<ToolsApi.RegexTemplate[]>([]);
const templateSelect = ref<string>();
const templateOptions = computed(() =>
  templateList.value.map((t) => ({
    label: `${t.name}（${t.category}）`,
    value: t.id,
  })),
);

async function loadTemplateList() {
  try {
    templateList.value = await getRegexTemplateListApi();
  } catch {
    // 下拉联动失败不阻塞主流程，管理 Tab 能看到具体错误
  }
}

function onTemplateSelect(id: unknown) {
  if (typeof id !== 'string') return; // 清空（undefined）或异常值直接忽略
  const tpl = templateList.value.find((t) => t.id === id);
  if (!tpl) return;
  testPattern.value = tpl.pattern;
  testFlags.value = tpl.flags ? tpl.flags.split('') : [];
  testResult.value = undefined;
}

/** 匹配高亮分段：纯数据切片 + v-for 渲染，杜绝 v-html 注入 */
const segments = computed<{ hit: boolean; text: string }[]>(() => {
  const res = testResult.value;
  if (!res) return [];
  if (!res.valid) return [{ hit: false, text: testedText.value }];
  const out: { hit: boolean; text: string }[] = [];
  let cursor = 0;
  for (const m of res.matches) {
    if (m.index > cursor) {
      out.push({ hit: false, text: testedText.value.slice(cursor, m.index) });
    }
    if (m.end > m.index) {
      out.push({ hit: true, text: testedText.value.slice(m.index, m.end) });
    }
    cursor = Math.max(cursor, m.end);
  }
  if (cursor < testedText.value.length) {
    out.push({ hit: false, text: testedText.value.slice(cursor) });
  }
  return out;
});

function visibleGroups(m: ToolsApi.RegexMatch) {
  return m.groups.filter((g) => g.index > 0);
}

async function onTest() {
  if (!testPattern.value.trim()) {
    message.warning('请先输入正则表达式');
    return;
  }
  if (!testText.value) {
    message.warning('请先输入待匹配文本');
    return;
  }
  testing.value = true;
  try {
    testResult.value = await testRegexApi({
      flags: testFlags.value.join(''),
      pattern: testPattern.value,
      replacement: replacement.value === '' ? undefined : replacement.value,
      text: testText.value,
    });
    testedText.value = testText.value;
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    testing.value = false;
  }
}

// ---- 常用模板 Tab ----
const tplLoading = ref(false);
const tplRecords = ref<ToolsApi.RegexTemplate[]>([]);
const tplTotal = ref(0);
const tplQuery = reactive({
  // allowClear 清空后 Select 会写入 undefined，类型上放开（antd 不接受 null）
  category: '' as string | undefined,
  keyword: '',
  pageNum: 1,
  pageSize: 10,
});
const editing = ref(false);
// 编辑中的模板主键：与 quick-nav 同因，vben 表单 setValues 会丢 schema 外的键，
// id 必须单独持有，否则提交时 PUT 打不到目标行。
const editingId = ref<string>();

const tplColumns = [
  { dataIndex: 'name', title: '名称', width: 160 },
  { dataIndex: 'category', key: 'category', title: '分类', width: 80 },
  { dataIndex: 'pattern', key: 'pattern', title: '正则' },
  { dataIndex: 'description', ellipsis: true, title: '说明', width: 200 },
  { dataIndex: 'sort', title: '排序', width: 64 },
  { key: 'action', title: '操作', width: 170 },
];

async function loadTemplatePage() {
  tplLoading.value = true;
  try {
    const res = await getRegexTemplatePageApi({
      category: tplQuery.category || undefined,
      keyword: tplQuery.keyword || undefined,
      pageNum: tplQuery.pageNum,
      pageSize: tplQuery.pageSize,
    });
    tplRecords.value = res.records ?? [];
    tplTotal.value = res.total ?? 0;
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    tplLoading.value = false;
  }
}

function searchTemplates() {
  tplQuery.pageNum = 1;
  loadTemplatePage();
}

function onTplTableChange(pag: { current?: number; pageSize?: number }) {
  tplQuery.pageNum = pag.current ?? 1;
  tplQuery.pageSize = pag.pageSize ?? 10;
  loadTemplatePage();
}

/** 从管理列表直接把模板带去测试 Tab（与测试 Tab 的下拉联动同一效果） */
function useTemplate(tpl: ToolsApi.RegexTemplate) {
  testPattern.value = tpl.pattern;
  testFlags.value = tpl.flags ? tpl.flags.split('') : [];
  testResult.value = undefined;
  templateSelect.value = undefined;
  tab.value = 'test';
}

const [TplForm, tplFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '模板名称（全局唯一）' },
      fieldName: 'name',
      label: '名称',
      rules: 'required',
    },
    {
      component: 'Textarea',
      componentProps: {
        placeholder: '正则表达式，如 1[3-9]\\d{9}',
        rows: 3,
      },
      fieldName: 'pattern',
      label: '正则',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '标志组合，如 im（i 忽略大小写 m 多行 s 点号通配 x 宽松 u Unicode）',
      },
      defaultValue: '',
      fieldName: 'flags',
      label: '标志',
    },
    {
      component: 'Select',
      componentProps: {
        options: CATEGORIES.map((c) => ({ label: c, value: c })),
      },
      defaultValue: '通用',
      fieldName: 'category',
      label: '分类',
    },
    {
      component: 'Textarea',
      componentProps: { placeholder: '这条模板解决什么问题', rows: 2 },
      fieldName: 'description',
      label: '说明',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '示例文本提示（可选）' },
      fieldName: 'sample',
      label: '示例',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0 },
      defaultValue: 0,
      fieldName: 'sort',
      label: '排序',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [TplModal, tplModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await tplFormApi.validate();
    if (!valid) return;
    const values = await tplFormApi.getValues();
    const body: ToolsApi.RegexTemplateBody = {
      category: values.category || '通用',
      description: values.description,
      flags: (values.flags || '').trim(),
      name: values.name,
      pattern: values.pattern,
      sample: values.sample,
      sort: values.sort ?? 0,
    };
    tplModalApi.lock();
    try {
      if (editing.value && editingId.value) {
        await updateRegexTemplateApi(editingId.value, body);
        message.success('修改成功');
      } else {
        await createRegexTemplateApi(body);
        message.success('新增成功');
      }
      tplModalApi.close();
      loadTemplatePage();
      loadTemplateList();
    } finally {
      tplModalApi.unlock();
    }
  },
});

function openTplCreate() {
  editing.value = false;
  editingId.value = undefined;
  tplFormApi.resetForm();
  tplFormApi.setValues({ category: '通用', flags: '', sort: 0 });
  tplModalApi.setData({ title: '新增模板' });
  tplModalApi.open();
}

function openTplEdit(row: ToolsApi.RegexTemplate) {
  editing.value = true;
  editingId.value = row.id;
  tplFormApi.resetForm();
  tplFormApi.setValues({
    category: row.category || '通用',
    description: row.description || '',
    flags: row.flags || '',
    name: row.name,
    pattern: row.pattern,
    sample: row.sample || '',
    sort: row.sort ?? 0,
  });
  tplModalApi.setData({ title: '编辑模板' });
  tplModalApi.open();
}

function confirmTplDelete(row: ToolsApi.RegexTemplate) {
  Modal.confirm({
    content: `确定删除模板「${row.name}」？删除后所有用户的联动下拉里都会消失。`,
    onOk: async () => {
      await deleteRegexTemplateApi(row.id);
      message.success('删除成功');
      loadTemplatePage();
      loadTemplateList();
    },
    title: '删除确认',
  });
}
</script>

<template>
  <Page
    description="按场景与条件生成正则；对已有正则做合法性校验、匹配高亮、捕获组提取、结构解析与替换预览；常用模板全局共享，可在测试时一键联动回填。"
    title="正则表达式工具"
  >
    <Alert class="mb-3" show-icon type="info">
      <template #message>
        安全上限：正则 ≤ {{ opts?.maxPatternLength ?? 1000 }} 字符、待匹配文本 ≤
        {{ opts?.maxTestTextLength ?? 100000 }} 字符、单次最多返回
        {{ opts?.maxMatches ?? 500 }} 条匹配。Java 无法对正则匹配设置超时，
        请勿粘贴来源不明的复杂正则（灾难性回溯会让匹配久算不完）。
      </template>
    </Alert>

    <Tabs v-model:activeKey="tab">
      <!-- ============ 生成 ============ -->
      <Tabs.TabPane key="generate" tab="生成">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card size="small" title="生成条件">
            <div class="mb-2 text-xs text-gray-500">场景</div>
            <Select
              v-model:value="scenario"
              :options="scenarioOptions"
              class="w-full"
            />
            <div v-if="currentScenario?.desc" class="mt-1 text-xs text-gray-400">
              {{ currentScenario.desc }}
            </div>

            <div
              v-for="f in currentScenario?.fields ?? []"
              :key="f.name"
              class="mt-3"
            >
              <div class="mb-1 text-xs text-gray-500">
                {{ f.label }}
                <span v-if="f.required" class="text-red-500">*</span>
              </div>
              <TextArea
                v-if="f.type === 'textarea'"
                v-model:value="params[f.name]"
                :placeholder="f.help || ''"
                :rows="3"
              />
              <InputNumber
                v-else-if="f.type === 'number'"
                v-model:value="params[f.name]"
                :max="f.max ?? undefined"
                :min="f.min ?? undefined"
                class="w-full"
              />
              <Select
                v-else-if="f.type === 'select'"
                v-model:value="params[f.name]"
                :options="(f.options ?? []).map((o) => ({ label: o.label, value: o.value }))"
                class="w-full"
              />
              <Switch
                v-else-if="f.type === 'switch'"
                v-model:checked="params[f.name]"
              />
              <Input
                v-else
                v-model:value="params[f.name]"
                :placeholder="f.help || ''"
              />
            </div>

            <div class="mt-3 mb-1 text-xs text-gray-500">标志（flags）</div>
            <Select
              v-model:value="genFlags"
              :options="flagOptions"
              class="w-full"
              mode="multiple"
              placeholder="不选表示无标志"
            />

            <Button
              v-if="hasAccessByCodes(['tools:regex:exec'])"
              :disabled="!canGenerate"
              :loading="generating"
              class="mt-4 w-full"
              type="primary"
              @click="onGenerate"
            >
              生成正则
            </Button>
          </Card>

          <Card size="small" title="生成结果">
            <div
              v-if="!genResult"
              class="py-16 text-center text-xs text-gray-400"
            >
              填写条件后点击「生成正则」
            </div>
            <template v-else>
              <div class="mb-1 text-xs text-gray-500">正则表达式</div>
              <TextArea :rows="3" :value="genResult.pattern" readonly />
              <div v-if="genResult.flags" class="mt-1 text-xs text-gray-400">
                标志：{{ genResult.flags }}
              </div>
              <Space class="mt-2" wrap>
                <Button size="small" @click="copyText(genResult.pattern)">
                  复制正则
                </Button>
                <Button
                  v-if="hasAccessByCodes(['tools:regex:exec'])"
                  size="small"
                  type="primary"
                  @click="fillTest(genResult.samples[0] ?? '')"
                >
                  填入测试
                </Button>
              </Space>

              <div class="mt-3 mb-1 text-xs text-gray-500">逐段说明</div>
              <ul class="space-y-1">
                <li
                  v-for="(line, i) in genResult.explanation"
                  :key="i"
                  class="text-xs text-gray-600"
                >
                  <code class="font-mono">{{ line }}</code>
                </li>
              </ul>

              <div class="mt-3 mb-1 text-xs text-gray-500">示例文本</div>
              <div class="flex flex-wrap gap-1">
                <Tag
                  v-for="(s, i) in genResult.samples"
                  :key="i"
                  class="cursor-pointer font-mono"
                  @click="fillTest(s)"
                >
                  {{ s }}
                </Tag>
                <span v-if="genResult.samples.length === 0" class="text-xs text-gray-400">
                  该场景请按字符集自行构造示例
                </span>
              </div>

              <div class="mt-3 mb-1 text-xs text-gray-500">注意事项</div>
              <ul class="space-y-1">
                <li
                  v-for="(n, i) in genResult.notes"
                  :key="i"
                  class="text-xs text-gray-400"
                >
                  · {{ n }}
                </li>
              </ul>
            </template>
          </Card>
        </div>
      </Tabs.TabPane>

      <!-- ============ 测试 · 解析 ============ -->
      <Tabs.TabPane key="test" tab="测试 · 解析">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card size="small" title="输入">
            <div class="mb-1 text-xs text-gray-500">从常用模板填入（联动）</div>
            <Select
              v-model:value="templateSelect"
              :options="templateOptions"
              allowClear
              class="w-full"
              placeholder="选择模板后自动回填正则与标志"
              @change="onTemplateSelect"
            />

            <div class="mt-3 mb-1 text-xs text-gray-500">
              正则表达式 <span class="text-red-500">*</span>
            </div>
            <TextArea
              v-model:value="testPattern"
              :rows="3"
              class="font-mono"
              placeholder="如 1[3-9]\d{9} 或从模板选择"
            />

            <div class="mt-3 mb-1 text-xs text-gray-500">标志（flags）</div>
            <Select
              v-model:value="testFlags"
              :options="flagOptions"
              class="w-full"
              mode="multiple"
              placeholder="不选表示无标志"
            />

            <div class="mt-3 mb-1 text-xs text-gray-500">
              待匹配文本 <span class="text-red-500">*</span>
            </div>
            <TextArea
              v-model:value="testText"
              :rows="6"
              placeholder="要被匹配的文本"
            />

            <div class="mt-3 mb-1 text-xs text-gray-500">替换串（可选）</div>
            <TextArea
              v-model:value="replacement"
              :rows="2"
              class="font-mono"
              placeholder="留空不做替换；支持 $1 / ${name} 引用捕获组"
            />

            <Button
              v-if="hasAccessByCodes(['tools:regex:exec'])"
              :loading="testing"
              class="mt-4 w-full"
              type="primary"
              @click="onTest"
            >
              执行测试
            </Button>
          </Card>

          <Card size="small" title="结果">
            <div
              v-if="!testResult"
              class="py-16 text-center text-xs text-gray-400"
            >
              输入正则与文本后点击「执行测试」
            </div>
            <template v-else>
              <Alert
                v-if="!testResult.valid"
                :message="testResult.errorMessage || '正则语法错误'"
                show-icon
                type="error"
              />
              <template v-else>
                <div class="mb-1 flex items-center gap-2 text-xs text-gray-500">
                  匹配预览
                  <Tag>{{ testResult.matchCount }} 处命中</Tag>
                  <Tag v-if="testResult.flagsApplied">
                    flags: {{ testResult.flagsApplied }}
                  </Tag>
                </div>
                <pre
                  class="max-h-40 overflow-auto rounded bg-gray-50 p-2 text-xs leading-5 whitespace-pre-wrap break-all"
                ><template v-for="(seg, i) in segments" :key="i"><mark v-if="seg.hit" class="rounded bg-yellow-200 px-0.5">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></pre>
                <div
                  v-if="testResult.truncated"
                  class="mt-1 text-xs text-orange-500"
                >
                  匹配数超过上限，仅展示前
                  {{ opts?.maxMatches ?? 500 }} 条
                </div>

                <div class="mt-3 mb-1 text-xs text-gray-500">匹配明细</div>
                <div class="max-h-60 space-y-1 overflow-auto">
                  <div
                    v-for="(m, i) in testResult.matches"
                    :key="i"
                    class="rounded border border-gray-100 p-2 text-xs"
                  >
                    <div class="flex items-center gap-2">
                      <Tag color="blue">#{{ i + 1 }}</Tag>
                      <span class="text-gray-400">[{{ m.index }}, {{ m.end }})</span>
                      <code class="font-mono">{{ m.text }}</code>
                    </div>
                    <div
                      v-if="visibleGroups(m).length"
                      class="mt-1 flex flex-wrap gap-1"
                    >
                      <span
                        v-for="g in visibleGroups(m)"
                        :key="g.index"
                        class="rounded bg-gray-100 px-1 py-0.5 font-mono"
                      >
                        组{{ g.index
                        }}<template v-if="g.name">({{ g.name }})</template>:
                        {{ g.value === null ? '未参与' : g.value }}
                      </span>
                    </div>
                  </div>
                </div>

                <div class="mt-3 mb-1 text-xs text-gray-500">结构解析</div>
                <div class="max-h-60 space-y-0.5 overflow-auto">
                  <div
                    v-for="(t, i) in testResult.structure"
                    :key="i"
                    :style="{ marginLeft: `${t.depth * 16}px` }"
                  >
                    <Tag :color="TOKEN_COLORS[t.type] || 'default'" class="font-mono">
                      {{ t.token }}
                    </Tag>
                    <span class="text-xs text-gray-400">{{ t.desc }}</span>
                  </div>
                </div>

                <template
                  v-if="testResult.replacementPreview !== null && testResult.replacementPreview !== undefined"
                >
                  <div class="mt-3 mb-1 text-xs text-gray-500">替换预览</div>
                  <TextArea
                    :rows="4"
                    :value="testResult.replacementPreview"
                    readonly
                  />
                </template>
              </template>
            </template>
          </Card>
        </div>
      </Tabs.TabPane>

      <!-- ============ 常用模板 ============ -->
      <Tabs.TabPane key="template" tab="常用模板">
        <Card size="small" title="模板管理（全局共享）">
          <Space class="mb-3" wrap>
            <Input
              v-model:value="tplQuery.keyword"
              allowClear
              class="w-56"
              placeholder="名称 / 说明关键字"
              @press-enter="searchTemplates"
            />
            <Select
              v-model:value="tplQuery.category"
              :options="CATEGORIES.map((c) => ({ label: c, value: c }))"
              allowClear
              class="w-32"
              placeholder="全部分类"
            />
            <Button @click="searchTemplates">查询</Button>
            <Button
              v-if="hasAccessByCodes(['tools:regex:template:add'])"
              type="primary"
              @click="openTplCreate"
            >
              新增模板
            </Button>
          </Space>

          <Table
            :columns="tplColumns"
            :data-source="tplRecords"
            :loading="tplLoading"
            :pagination="{
              current: tplQuery.pageNum,
              pageSize: tplQuery.pageSize,
              total: tplTotal,
              showSizeChanger: true,
              showTotal: (t: number) => `共 ${t} 条`,
            }"
            row-key="id"
            size="small"
            @change="onTplTableChange"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'category'">
                <Tag>{{ record.category }}</Tag>
              </template>
              <template v-else-if="column.key === 'pattern'">
                <code
                  class="block max-w-[360px] truncate font-mono text-xs"
                  :title="record.pattern"
                >
                  {{ record.pattern }}
                </code>
              </template>
              <template v-else-if="column.key === 'action'">
                <Space>
                  <Button
                    v-if="hasAccessByCodes(['tools:regex:exec'])"
                    size="small"
                    type="link"
                    @click="useTemplate(record as ToolsApi.RegexTemplate)"
                  >
                    测试
                  </Button>
                  <Button
                    v-if="hasAccessByCodes(['tools:regex:template:edit'])"
                    size="small"
                    type="link"
                    @click="openTplEdit(record as ToolsApi.RegexTemplate)"
                  >
                    编辑
                  </Button>
                  <Button
                    v-if="hasAccessByCodes(['tools:regex:template:delete'])"
                    danger
                    size="small"
                    type="link"
                    @click="confirmTplDelete(record as ToolsApi.RegexTemplate)"
                  >
                    删除
                  </Button>
                </Space>
              </template>
            </template>
          </Table>
        </Card>
      </Tabs.TabPane>
    </Tabs>

    <TplModal class="w-[560px]" title="正则模板">
      <TplForm />
    </TplModal>
  </Page>
</template>
