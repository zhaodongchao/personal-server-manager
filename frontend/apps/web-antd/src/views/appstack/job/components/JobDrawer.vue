<script lang="ts" setup>
/**
 * 任务新建 / 编辑抽屉。
 *
 * <p>表单<b>不是写死的</b>：处理器参数区按服务端下发的 HandlerSchema 动态渲染
 * （SHELL 的选择项还会按宿主侧是否真的装了该命令做禁用），这样以后新增一类处理器
 * 不需要动前端一行代码。
 *
 * <p>两处刻意的交互设计：
 * <ul>
 *   <li><b>cron 校验前置</b> —— 保存前就把「表达式是否合法 + 相邻间隔是否过短 + 未来
 *       5 次触发时间」摊给用户看。定时任务最贵的错误是「写了个每 5 秒执行一次的
 *       表达式」，它不会报错，只会把宿主的 mysqldump 打成 DDoS；</li>
 *   <li><b>服务保护清单联动</b> —— 选中受保护单元 + 破坏性动作时，确认关键字输入框
 *       自动出现并预填，用户在保存前就知道这条任务将来会被要求确认。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { JobApi } from '#/api';

import { computed, reactive, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  message,
  Select,
  Space,
  Switch,
  Tag,
} from 'ant-design-vue';

import {
  createJobApi,
  getJobCommandsApi,
  updateJobApi,
  validateJobCronApi,
} from '#/api';

defineOptions({ name: 'AppstackJobDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    editing?: JobApi.Job | null;
    executors?: JobApi.Executor[];
    handlers?: JobApi.HandlerSchema[];
    options?: JobApi.Options;
  }>(),
  {
    editing: null,
    executors: () => [],
    handlers: () => [],
  },
);

const emit = defineEmits<{
  'update:open': [boolean];
  saved: [];
}>();

const saving = ref(false);
const commands = ref<JobApi.CommandItem[]>([]);

const form = reactive({
  blockStrategy: 'SERIAL',
  confirmKeyword: '',
  cronExpr: '0 0 3 * * ?',
  executorId: undefined as number | string | undefined,
  handler: 'INTERNAL',
  jobDesc: '',
  jobName: '',
  retryCount: 0,
  routeStrategy: 'FIRST',
  status: 1,
  timeoutSec: 300,
});

const params = reactive<Record<string, any>>({});

const cronPreview = ref<JobApi.CronPreview>();
const cronChecking = ref(false);

const currentSchema = computed(() =>
  props.handlers.find((item) => item.type === form.handler),
);

const currentFields = computed(() => currentSchema.value?.fields ?? []);

const handlerOptions = computed(() =>
  props.handlers.map((item) => ({
    label: `${item.label}（${item.type}）`,
    value: item.type,
  })),
);

const executorOptions = computed(() =>
  props.executors.map((item) => ({
    label: `${item.executorName || item.appName}（${item.status}）`,
    value: item.id,
  })),
);

const routeOptions = (props.options?.routeStrategies ?? []).map((v) => ({
  label: v,
  value: v,
}));
const blockOptions = (props.options?.blockStrategies ?? []).map((v) => ({
  label: v,
  value: v,
}));

const minIntervalSeconds = computed(
  () => props.options?.minIntervalSeconds ?? 10,
);
const maxTimeoutSeconds = computed(() => props.options?.maxTimeoutSeconds ?? 900);
const maxRetryCount = computed(() => props.options?.maxRetryCount ?? 3);

/** 是否命中「服务保护清单 + 破坏性动作」 */
const needConfirm = computed(() => {
  if (form.handler !== 'SERVICE') return false;
  const unit = String(params.unit ?? '');
  const action = String(params.action ?? '');
  if (!unit || !action) return false;
  const destructive = props.options?.destructiveServiceActions ?? [];
  const protectedUnits = props.options?.protectedUnits ?? [];
  return destructive.includes(action) && protectedUnits.includes(unit);
});

const suggestedKeyword = computed(() =>
  params.unit ? `APPLY ${params.unit}` : '',
);

watch(needConfirm, (need) => {
  if (need && !form.confirmKeyword) {
    form.confirmKeyword = suggestedKeyword.value;
  }
});

watch(
  () => props.open,
  (open) => {
    if (open) void init();
  },
);

function isBlank(value: any) {
  return (
    value === undefined ||
    value === null ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

function parseParam(raw?: string): Record<string, any> {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function resetParams() {
  for (const key of Object.keys(params)) {
    delete params[key];
  }
}

/** 必填的枚举字段给个默认值，省掉「保存时才被告知没选」 */
function fillDefaults() {
  for (const field of currentFields.value) {
    if (!field.required || !isBlank(params[field.name])) continue;
    if (form.handler === 'INTERNAL' && field.name === 'task') {
      const first = props.options?.internalTasks?.[0]?.code;
      if (first) params[field.name] = first;
    } else if (field.type === 'select' && field.options?.length) {
      params[field.name] = field.options[0];
    }
  }
}

async function init() {
  cronPreview.value = undefined;
  resetParams();
  const job = props.editing;
  if (job) {
    Object.assign(form, {
      blockStrategy: job.blockStrategy || 'SERIAL',
      confirmKeyword: job.confirmKeyword ?? '',
      cronExpr: job.cronExpr,
      executorId: job.executorId,
      handler: job.handler,
      jobDesc: job.jobDesc ?? '',
      jobName: job.jobName,
      retryCount: job.retryCount ?? 0,
      routeStrategy: job.routeStrategy || 'FIRST',
      status: job.status ?? 1,
      timeoutSec: job.timeoutSec ?? 300,
    });
    Object.assign(params, parseParam(job.handlerParam));
    fillDefaults();
    void checkCron();
  } else {
    const builtin = props.executors.find((item) => item.type === 'BUILTIN');
    Object.assign(form, {
      blockStrategy: 'SERIAL',
      confirmKeyword: '',
      cronExpr: '0 0 3 * * ?',
      executorId: builtin?.id ?? props.executors[0]?.id,
      handler: 'INTERNAL',
      jobDesc: '',
      jobName: '',
      retryCount: 0,
      routeStrategy: 'FIRST',
      status: 1,
      timeoutSec: 300,
    });
    fillDefaults();
  }
}

async function loadCommands() {
  if (commands.value.length > 0) return;
  try {
    commands.value = await getJobCommandsApi();
  } catch {
    commands.value = [];
  }
}

function onHandlerChange() {
  resetParams();
  cronPreview.value = undefined;
  form.confirmKeyword = '';
  fillDefaults();
  if (form.handler === 'SHELL') void loadCommands();
}

function selectOptions(field: JobApi.HandlerField) {
  if (form.handler === 'SHELL' && field.name === 'command') {
    if (commands.value.length > 0) {
      return commands.value.map((item) => ({
        disabled: !item.available,
        label: item.available ? item.name : `${item.name}（宿主未安装）`,
        value: item.name,
      }));
    }
    return (field.options ?? []).map((v) => ({ label: v, value: v }));
  }
  if (form.handler === 'INTERNAL' && field.name === 'task') {
    return (props.options?.internalTasks ?? []).map((item) => ({
      label: `${item.code} —— ${item.label}`,
      value: item.code,
    }));
  }
  return (field.options ?? []).map((v) => ({ label: v, value: v }));
}

function fieldHelp(field: JobApi.HandlerField) {
  if (form.handler === 'INTERNAL' && field.name === 'task') {
    const code = params.task;
    const hit = props.options?.internalTasks?.find((item) => item.code === code);
    return hit?.description ?? field.help;
  }
  return field.help;
}

async function checkCron() {
  const expr = String(form.cronExpr ?? '').trim();
  if (!expr) {
    cronPreview.value = undefined;
    return;
  }
  cronChecking.value = true;
  try {
    cronPreview.value = await validateJobCronApi(expr);
  } catch {
    cronPreview.value = undefined;
  } finally {
    cronChecking.value = false;
  }
}

/** 需要按 JSON 对象提交的字段 */
const JSON_FIELDS = new Set(['headers', 'params']);

function buildPayload(): Record<string, any> {
  const payload: Record<string, any> = {};
  for (const field of currentFields.value) {
    const raw = params[field.name];
    if (isBlank(raw)) continue;
    if (JSON_FIELDS.has(field.name)) {
      if (typeof raw === 'string') {
        try {
          payload[field.name] = JSON.parse(raw);
        } catch {
          throw new Error(`${field.label} 不是合法 JSON 对象`);
        }
      } else {
        payload[field.name] = raw;
      }
    } else if (field.type === 'number') {
      const num = Number(raw);
      if (Number.isNaN(num)) {
        throw new Error(`${field.label} 需为数字`);
      }
      payload[field.name] = num;
    } else {
      payload[field.name] = raw;
    }
  }
  return payload;
}

async function submit() {
  if (isBlank(form.jobName)) {
    message.warning('请填写任务名');
    return;
  }
  if (!/^[A-Za-z0-9_\-.:]+$/.test(form.jobName.trim())) {
    message.warning('任务名只允许字母、数字、下划线、短横线、点与冒号');
    return;
  }
  if (isBlank(form.cronExpr)) {
    message.warning('请填写 cron 表达式');
    return;
  }
  if (isBlank(form.executorId)) {
    message.warning('请选择执行器');
    return;
  }
  const missing = currentFields.value.filter(
    (field) => field.required && isBlank(params[field.name]),
  );
  if (missing.length > 0) {
    message.warning(`请填写：${missing.map((f) => f.label).join('、')}`);
    return;
  }
  if (needConfirm.value && isBlank(form.confirmKeyword)) {
    message.warning(`该动作命中服务保护清单，请填写确认关键字 ${suggestedKeyword.value}`);
    return;
  }
  if (cronPreview.value && cronPreview.value.valid === false) {
    message.warning(cronPreview.value.message || 'cron 表达式不可用');
    return;
  }

  let handlerParam: Record<string, any>;
  try {
    handlerParam = buildPayload();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '处理器参数有误');
    return;
  }

  const body: JobApi.JobBody = {
    blockStrategy: form.blockStrategy,
    confirmKeyword: form.confirmKeyword.trim() || undefined,
    cronExpr: form.cronExpr.trim(),
    executorId: form.executorId!,
    handler: form.handler,
    handlerParam,
    jobDesc: form.jobDesc.trim() || undefined,
    jobName: form.jobName.trim(),
    retryCount: form.retryCount,
    routeStrategy: form.routeStrategy,
    status: form.status,
    timeoutSec: form.timeoutSec,
  };

  saving.value = true;
  try {
    if (props.editing?.id) {
      await updateJobApi(props.editing.id, body);
      message.success('保存成功，调度已刷新');
    } else {
      await createJobApi(body);
      message.success('创建成功，调度已装载');
    }
    emit('update:open', false);
    emit('saved');
  } catch {
    // 错误提示由请求拦截器统一给出
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Drawer
    :open="open"
    :title="editing ? `编辑任务：${editing.jobName}` : '新增定时任务'"
    :width="720"
    @close="emit('update:open', false)"
  >
    <Form layout="vertical">
      <div class="grid grid-cols-2 gap-x-3">
        <Form.Item label="任务名" required>
          <Input
            v-model:value="form.jobName"
            :disabled="!!editing"
            placeholder="如 backup-main-db（字母数字下划线短横线点冒号）"
          />
        </Form.Item>
        <Form.Item label="执行器" required>
          <Select
            v-model:value="form.executorId"
            :options="executorOptions"
            placeholder="选择执行器"
          />
        </Form.Item>
      </div>

      <Form.Item label="描述">
        <Input v-model:value="form.jobDesc" placeholder="这条任务是干什么的" />
      </Form.Item>

      <!-- ==================== cron ==================== -->
      <Form.Item label="Cron 表达式" required>
        <Space.Compact class="w-full">
          <Input
            v-model:value="form.cronExpr"
            class="font-mono"
            placeholder="Spring 6 段式，如 0 0 3 * * ?"
            @blur="checkCron"
            @press-enter="checkCron"
          />
          <Button :loading="cronChecking" @click="checkCron">校验</Button>
        </Space.Compact>
        <div class="mt-1 text-xs text-gray-500">
          6 段式：秒 分 时 日 月 周；相邻触发间隔不得小于
          {{ minIntervalSeconds }} 秒。
        </div>
      </Form.Item>

      <Alert
        v-if="cronPreview"
        class="mb-3"
        :type="cronPreview.valid ? 'success' : 'warning'"
        show-icon
        :message="
          cronPreview.valid
            ? `表达式可用，相邻间隔 ${cronPreview.intervalSeconds ?? '-'} 秒`
            : cronPreview.message || '表达式不可用'
        "
      >
        <template v-if="cronPreview.nextTimes?.length" #description>
          <div class="text-xs">
            未来 5 次：
            <span class="font-mono">{{ cronPreview.nextTimes.join(' · ') }}</span>
          </div>
        </template>
      </Alert>

      <!-- ==================== 处理器 ==================== -->
      <Form.Item label="处理器" required>
        <Select
          v-model:value="form.handler"
          :options="handlerOptions"
          @change="onHandlerChange"
        />
      </Form.Item>

      <Alert
        v-if="currentSchema"
        class="mb-3"
        show-icon
        type="info"
        :message="currentSchema.description"
      />

      <Form.Item
        v-for="field in currentFields"
        :key="field.name"
        :label="field.label"
        :required="field.required"
      >
        <Select
          v-if="field.type === 'select'"
          v-model:value="params[field.name]"
          :options="selectOptions(field)"
          allow-clear
          :placeholder="field.placeholder || '请选择'"
        />
        <InputNumber
          v-else-if="field.type === 'number'"
          v-model:value="params[field.name]"
          class="w-full"
          :placeholder="field.placeholder || ''"
        />
        <Input.TextArea
          v-else-if="field.type === 'textarea'"
          v-model:value="params[field.name]"
          :rows="3"
          class="font-mono"
          :placeholder="field.placeholder || ''"
        />
        <Input
          v-else
          v-model:value="params[field.name]"
          :placeholder="field.placeholder || ''"
        />
        <div v-if="fieldHelp(field)" class="mt-1 text-xs whitespace-pre-line text-gray-500">
          {{ fieldHelp(field) }}
        </div>
      </Form.Item>

      <Form.Item v-if="needConfirm" label="确认关键字" required>
        <Input
          v-model:value="form.confirmKeyword"
          class="font-mono"
          :placeholder="suggestedKeyword"
        />
        <div class="mt-1 text-xs text-gray-500">
          该服务在保护清单内，且动作属破坏性；执行前会再次校验此关键字。
        </div>
      </Form.Item>

      <!-- ==================== 调度语义 ==================== -->
      <div class="grid grid-cols-2 gap-x-3">
        <Form.Item label="阻塞策略">
          <Select v-model:value="form.blockStrategy" :options="blockOptions" />
        </Form.Item>
        <Form.Item label="路由策略">
          <Select v-model:value="form.routeStrategy" :options="routeOptions" />
        </Form.Item>
        <Form.Item label="超时（秒）">
          <InputNumber
            v-model:value="form.timeoutSec"
            :max="maxTimeoutSeconds"
            :min="1"
            class="w-full"
          />
        </Form.Item>
        <Form.Item label="失败重试次数">
          <InputNumber
            v-model:value="form.retryCount"
            :max="maxRetryCount"
            :min="0"
            class="w-full"
          />
          <div class="mt-1 text-xs text-gray-500">
            固定 5 秒后重试，重试记录写入同一条日志（retry_index 递增）。
          </div>
        </Form.Item>
      </div>

      <Form.Item label="保存后立即启用">
        <Space>
          <Switch
            :checked="form.status === 1"
            @change="(checked: any) => (form.status = checked ? 1 : 0)"
          />
          <Tag v-if="form.status === 1" color="success">启用</Tag>
          <Tag v-else>停用</Tag>
        </Space>
      </Form.Item>
    </Form>

    <template #footer>
      <Space>
        <Button @click="emit('update:open', false)">取消</Button>
        <Button :loading="saving" type="primary" @click="submit">保存</Button>
      </Space>
    </template>
  </Drawer>
</template>
