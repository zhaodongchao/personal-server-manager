<script lang="ts" setup>
/**
 * 计划任务新增/编辑弹窗
 *
 * 三处与旧版不同的关键点：
 * 1. **边填边校验**：cron 表达式改动后防抖调用 /preview，实时给出「人话描述 +
 *    未来 5 次执行时间」，填错在保存前就能发现，而不是保存后才发现任务不跑。
 * 2. **命令白名单提示**：下拉取自后端（含宿主机可用性标注），避免用户选了一个
 *    「允许但宿主机没装」的命令，保存后才发现执行失败。
 * 3. **策略可配**：错过执行策略、并发策略、连续失败自动停用阈值都暴露出来——
 *    这三项决定了任务在异常情况下的行为，藏在默认值里迟早出事。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  TypographyText,
  message,
} from 'ant-design-vue';

import { getCronWhitelistApi, previewCronExprApi } from '#/api';

import {
  CRON_TEMPLATES,
  MISFIRE_OPTIONS,
  OVERLAP_OPTIONS,
  fmtTime,
} from '../utils';

defineOptions({ name: 'OpsCronFormDrawer' });

const props = defineProps<{
  open: boolean;
  /** 传入则为编辑，null 为新增 */
  record: OpsApi.CronJob | null;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  submit: [OpsApi.CronJob];
}>();

const submitting = ref(false);
const whitelist = ref<OpsApi.CronWhitelist>();
/** 命令首词（用于匹配白名单提示） */
const commandOptions = computed(() => {
  const list = whitelist.value?.allowed ?? [];
  const available = new Set(whitelist.value?.available ?? []);
  return list.map((cmd) => ({
    label: available.has(cmd) ? cmd : `${cmd}（宿主机缺失）`,
    value: cmd,
  }));
});

/** 预览 */
const preview = ref<OpsApi.CronPreview>();
const previewing = ref(false);
let previewTimer: ReturnType<typeof setTimeout> | undefined;

const form = ref<OpsApi.CronJob>(blank());

function blank(): OpsApi.CronJob {
  return {
    name: '',
    cronExpr: '0 2 * * *',
    command: '',
    timeoutSec: 300,
    status: 1,
    misfirePolicy: 'skip',
    overlapPolicy: 'skip',
    maxFail: 0,
    remark: '',
  };
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    form.value = props.record
      ? {
          ...blank(),
          ...props.record,
          misfirePolicy: props.record.misfirePolicy ?? 'skip',
          overlapPolicy: props.record.overlapPolicy ?? 'skip',
          maxFail: props.record.maxFail ?? 0,
        }
      : blank();
    void runPreview();
    void loadWhitelist();
  },
);

async function loadWhitelist() {
  if (whitelist.value) return;
  try {
    whitelist.value = await getCronWhitelistApi();
  } catch {
    whitelist.value = undefined;
  }
}

/** 表达式变化后防抖预览，避免每敲一个字符就打一次接口 */
function schedulePreview() {
  preview.value = undefined;
  if (previewTimer) clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, 400);
}

async function runPreview() {
  const expr = form.value.cronExpr?.trim();
  if (!expr) {
    preview.value = undefined;
    return;
  }
  previewing.value = true;
  try {
    preview.value = await previewCronExprApi({ cronExpr: expr, count: 5 });
  } catch {
    preview.value = undefined;
  } finally {
    previewing.value = false;
  }
}

function applyTemplate(value: string) {
  form.value.cronExpr = value;
  void runPreview();
}

function pickCommand(value: string) {
  const current = form.value.command?.trim() ?? '';
  const parts = current.split(/\s+/).filter(Boolean);
  parts[0] = value;
  form.value.command = parts.join(' ');
}

/** 命令首词是否不在白名单（前端先提示，后端仍会再校验一次） */
const commandWarn = computed(() => {
  const first = (form.value.command ?? '').trim().split(/\s+/)[0] ?? '';
  if (!first) return '';
  const list = whitelist.value?.allowed ?? [];
  if (!list.length || list.includes(first)) return '';
  return `「${first}」不在可执行命令白名单内，保存会被拒绝`;
});

async function onSubmit() {
  if (!form.value.name?.trim()) {
    message.warning('请填写任务名');
    return;
  }
  if (!form.value.cronExpr?.trim()) {
    message.warning('请填写 cron 表达式');
    return;
  }
  if (preview.value && !preview.value.valid) {
    message.warning(`cron 表达式无效：${preview.value.message ?? '请检查表达式'}`);
    return;
  }
  if (!form.value.command?.trim()) {
    message.warning('请填写命令');
    return;
  }
  if (commandWarn.value) {
    message.warning(commandWarn.value);
    return;
  }
  submitting.value = true;
  try {
    emit('submit', {
      ...form.value,
      name: form.value.name.trim(),
      cronExpr: form.value.cronExpr.trim(),
      command: form.value.command.trim(),
    });
  } finally {
    submitting.value = false;
  }
}

function onCancel() {
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="props.open"
    :title="props.record ? '编辑计划任务' : '新增计划任务'"
    :width="720"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    @cancel="onCancel"
    @ok="onSubmit"
  >
    <Form layout="vertical" class="pt-2">
      <Form.Item label="任务名" required>
        <Input
          v-model:value="form.name"
          placeholder="例如：每日清理临时文件"
          :maxlength="60"
          show-count
        />
      </Form.Item>

      <Form.Item label="cron 表达式" required>
        <Space direction="vertical" class="w-full" :size="8">
          <Input
            v-model:value="form.cronExpr"
            placeholder="分 时 日 月 周，例如 0 2 * * *"
            @change="schedulePreview"
          />
          <Space :size="6" wrap>
            <TypographyText type="secondary">常用模板：</TypographyText>
            <Tag
              v-for="tpl in CRON_TEMPLATES"
              :key="tpl.value"
              class="cursor-pointer"
              @click="applyTemplate(tpl.value)"
            >
              {{ tpl.label }}
            </Tag>
          </Space>

          <div
            v-if="previewing"
            class="rounded bg-gray-50 px-3 py-2 text-xs text-gray-400"
          >
            正在校验表达式…
          </div>
          <Alert
            v-else-if="preview && !preview.valid"
            type="error"
            show-icon
            :message="`表达式无效：${preview.message ?? '请检查'}`"
          />
          <div
            v-else-if="preview && preview.valid"
            class="rounded bg-gray-50 px-3 py-2"
          >
            <div class="mb-1 text-xs text-gray-500">
              人话：
              <span class="font-medium text-gray-800">
                {{ preview.humanExpr || preview.cronExpr }}
              </span>
            </div>
            <div class="text-xs text-gray-500">未来 5 次执行：</div>
            <div class="mt-1 flex flex-wrap gap-1">
              <Tag
                v-for="t in preview.nextTimes"
                :key="t"
                color="blue"
                class="m-0"
              >
                {{ fmtTime(t) }}
              </Tag>
              <TypographyText v-if="!preview.nextTimes?.length" type="secondary">
                该表达式在未来不再触发
              </TypographyText>
            </div>
          </div>
        </Space>
      </Form.Item>

      <Form.Item label="命令" required>
        <Space direction="vertical" class="w-full" :size="8">
          <Input
            v-model:value="form.command"
            placeholder="例如：systemctl status nginx"
          />
          <Space :size="6" wrap>
            <TypographyText type="secondary">可用命令：</TypographyText>
            <Tag
              v-for="cmd in commandOptions"
              :key="cmd.value"
              class="cursor-pointer"
              @click="pickCommand(cmd.value)"
            >
              {{ cmd.label }}
            </Tag>
          </Space>
          <Alert
            v-if="commandWarn"
            type="warning"
            show-icon
            :message="commandWarn"
          />
          <Alert
            v-else-if="whitelist && !whitelist.hostAvailable"
            type="warning"
            show-icon
            message="宿主执行通道不可用，任务将无法真正执行"
          />
          <TypographyText type="secondary" class="text-xs">
            支持引号，例如
            <code>df -h /</code>；命令在宿主机上执行，首词必须在白名单内。
          </TypographyText>
        </Space>
      </Form.Item>

      <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
        <Form.Item label="超时（秒）">
          <InputNumber
            v-model:value="form.timeoutSec"
            :min="1"
            :max="86_400"
            class="w-full"
          />
        </Form.Item>
        <Form.Item label="连续失败自动停用阈值">
          <InputNumber
            v-model:value="form.maxFail"
            :min="0"
            :max="99"
            class="w-full"
          />
          <TypographyText type="secondary" class="text-xs">
            0 表示不自动停用
          </TypographyText>
        </Form.Item>
        <Form.Item label="错过执行策略">
          <Select
            v-model:value="form.misfirePolicy"
            :options="MISFIRE_OPTIONS"
          />
        </Form.Item>
        <Form.Item label="并发策略">
          <Select v-model:value="form.overlapPolicy" :options="OVERLAP_OPTIONS" />
        </Form.Item>
      </div>

      <Form.Item label="状态">
        <Switch
          v-model:checked="form.status"
          :checked-value="1"
          :un-checked-value="0"
          checked-children="启用"
          un-checked-children="停用"
        />
      </Form.Item>

      <Divider class="my-2" />
      <Form.Item label="备注">
        <Input.TextArea
          v-model:value="form.remark"
          :rows="2"
          :maxlength="255"
          placeholder="可选"
        />
      </Form.Item>
    </Form>
  </Modal>
</template>
