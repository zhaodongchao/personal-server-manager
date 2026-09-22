<script lang="ts" setup>
/**
 * 「一键生效」预演弹窗。
 *
 * <p>本弹窗是 9 道闸门里最靠前、也最容易被使用者忽略的几道：
 * <ol>
 *   <li>展示宿主机 dry-run（{@code sys.validate}）的真实输出——失败时原文可见，而不是只说一句「校验失败」；</li>
 *   <li>展示行级 diff，让人看清<b>到底改了哪几行</b>；</li>
 *   <li>展示服务端规则错误（任一非空即禁止提交按钮）；</li>
 *   <li>L3 类别要求键入后端下发的关键字，并勾选风险确认。</li>
 * </ol>
 *
 * <p>「有变更」的判定与最终写入都以服务端返回为准：本弹窗不做本地 diff 计算，
 * 因为服务端还要处理空值=不托管、timesync 的 section 包装等语义。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { ServerConfigApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  Modal,
  Space,
  Spin,
  Tag,
  message,
} from 'ant-design-vue';

import { previewServerConfigApi } from '#/api';

import { parseDiff, riskMeta } from '../utils';

defineOptions({ name: 'OpsServerConfigApplyPreviewModal' });

const props = defineProps<{
  open: boolean;
  categoryKey: string;
  categoryName?: string;
  riskLevel?: string;
  applyHint?: string;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  /** 预演通过且关键字校验通过后触发，父组件真正调用生效接口 */
  apply: [string];
}>();

const loading = ref(false);
const preview = ref<ServerConfigApi.Preview>();
const loadError = ref('');
const typed = ref('');
const acked = ref(false);

const risk = computed(() => riskMeta(props.riskLevel));
const keyword = computed(() => preview.value?.applyKeyword || '');
const needConfirm = computed(() => preview.value?.needConfirm === true);
const ruleErrors = computed(() => preview.value?.ruleErrors || []);
const warnings = computed(() => preview.value?.warnings || []);
const diffLines = computed(() => parseDiff(preview.value?.diff));

const canApply = computed(
  () =>
    !!preview.value &&
    !loading.value &&
    ruleErrors.value.length === 0 &&
    preview.value.validateOk === true &&
    (preview.value.content || '').length > 0,
);

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    typed.value = '';
    acked.value = false;
    preview.value = undefined;
    loadError.value = '';
    loading.value = true;
    try {
      preview.value = await previewServerConfigApi(props.categoryKey);
    } catch (error) {
      loadError.value =
        error instanceof Error ? error.message : '预演接口调用失败';
    } finally {
      loading.value = false;
    }
  },
);

function onApply() {
  if (!canApply.value) {
    message.error('预演未通过，禁止生效');
    return;
  }
  if (needConfirm.value) {
    if (!acked.value) {
      message.warning('请先勾选「我已确认风险」');
      return;
    }
    if (typed.value.trim() !== keyword.value) {
      message.error(`确认关键字不匹配，请输入 ${keyword.value}`);
      return;
    }
  }
  emit('apply', typed.value.trim());
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :ok-button-props="{ danger: needConfirm }"
    :ok-text="needConfirm ? '确认生效（高风险）' : '确认生效'"
    :open="open"
    :title="`一键生效预演 · ${categoryName || categoryKey}`"
    :width="880"
    @cancel="emit('update:open', false)"
    @ok="onApply"
  >
    <Spin :spinning="loading">
      <Alert v-if="loadError" :message="loadError" show-icon type="error" />

      <template v-else-if="preview">
        <div class="mb-2 flex flex-wrap items-center gap-2 text-xs">
          <Tag :color="risk.color">{{ risk.label }}</Tag>
          <Tag :color="preview.changed ? 'blue' : 'default'">
            {{ preview.changed ? '有变更' : '与当前托管内容一致' }}
          </Tag>
          <span class="text-gray-500">
            托管项 {{ preview.managedItemCount ?? 0 }} 个
          </span>
        </div>

        <div class="mb-2 font-mono text-xs text-gray-500 dark:text-gray-400">
          {{ preview.targetPath }}
        </div>

        <Alert
          v-if="ruleErrors.length > 0"
          class="mb-2"
          show-icon
          type="error"
          :message="`服务端规则校验未通过（${ruleErrors.length} 项），禁止生效`"
        >
          <template #description>
            <ul class="ml-4 list-disc">
              <li v-for="(err, index) in ruleErrors" :key="index">{{ err }}</li>
            </ul>
          </template>
        </Alert>

        <Alert
          v-if="warnings.length > 0"
          class="mb-2"
          show-icon
          type="warning"
          message="提示（不阻断生效）"
        >
          <template #description>
            <ul class="ml-4 list-disc">
              <li v-for="(w, index) in warnings" :key="index">{{ w }}</li>
            </ul>
          </template>
        </Alert>

        <Alert
          v-if="preview.applyHint || applyHint"
          class="mb-2"
          show-icon
          type="info"
          :message="preview.applyHint || applyHint"
        />

        <div class="mb-1 flex items-center gap-2 text-sm">
          <span>将写入的内容</span>
          <Tag :color="preview.validateOk ? 'green' : 'red'">
            dry-run {{ preview.validateOk ? '通过' : '失败' }}
          </Tag>
        </div>
        <pre
          class="mb-2 max-h-48 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
        >{{ preview.content || '（空：没有任何配置项被托管）' }}</pre>

        <pre
          v-if="preview.validateOutput"
          class="mb-2 max-h-32 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 text-gray-600 dark:bg-white/5 dark:text-gray-300"
        >{{ preview.validateOutput }}</pre>

        <div class="mb-1 text-sm">变更差异（行级 diff）</div>
        <div
          class="mb-3 max-h-56 overflow-auto rounded border border-gray-200 p-2 font-mono text-xs leading-5 dark:border-gray-700"
        >
          <div
            v-for="(line, index) in diffLines"
            :key="index"
            class="whitespace-pre"
            :class="line.kind === 'add' ? 'bg-green-50 text-green-700 dark:bg-green-950/40 dark:text-green-300' : line.kind === 'del' ? 'bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300' : 'text-gray-500 dark:text-gray-400'"
          >
            {{ (line.kind === 'add' ? '+ ' : line.kind === 'del' ? '- ' : '  ') + line.text }}
          </div>
        </div>

        <template v-if="needConfirm">
          <Alert
            class="mb-2"
            show-icon
            type="warning"
            :message="`该类别属高风险变更，需键入「${keyword}」并勾选确认后才能生效`"
          />
          <Space direction="vertical" class="w-full">
            <Input v-model:value="typed" :placeholder="keyword" allow-clear />
            <Checkbox v-model:checked="acked">
              <span class="text-xs">
                我已确认风险：清楚该操作会改写系统配置并触发服务重载/重启，且已保留其他可登录途径。
              </span>
            </Checkbox>
          </Space>
        </template>
      </template>
    </Spin>

    <template #footer>
      <Button @click="emit('update:open', false)">取消</Button>
      <Button
        danger
        :disabled="!canApply"
        :loading="submitting"
        type="primary"
        @click="onApply"
      >
        {{ needConfirm ? '确认生效（高风险）' : '确认生效' }}
      </Button>
    </template>
  </Modal>
</template>
