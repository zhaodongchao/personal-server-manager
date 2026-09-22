<script lang="ts" setup>
/**
 * 配置项新增 / 编辑弹窗。
 *
 * <p>关键语义：<b>值留空 = 不托管该项</b>。本模块只把非空项写进 drop-in 片段，
 * 所以「清空值」是一条合法操作（等价于让该项回到发行版默认值），
 * 弹窗里对应显式提示，避免使用者以为「必须填点什么」。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { ServerConfigApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Input,
  InputNumber,
  Modal,
  Select,
  message,
} from 'ant-design-vue';

import { categoryMeta } from '../utils';

defineOptions({ name: 'OpsServerConfigItemFormModal' });

const props = defineProps<{
  open: boolean;
  categoryKey: string;
  /** 为空 = 新增 */
  item?: null | ServerConfigApi.Item;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  submit: [ServerConfigApi.ItemBody];
}>();

const VALUE_TYPES = [
  { label: 'int（整数）', value: 'int' },
  { label: 'bool（yes / no）', value: 'bool' },
  { label: 'enum（枚举）', value: 'enum' },
  { label: 'string（字符串）', value: 'string' },
  { label: 'text（多值 / 带空格）', value: 'text' },
];

const form = ref<ServerConfigApi.ItemBody & { optionsText?: string }>({});

const isEdit = computed(() => !!props.item?.itemKey);
const meta = computed(() => categoryMeta(props.categoryKey));

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    const it = props.item;
    form.value = it
      ? {
          itemKey: it.itemKey,
          itemValue: it.itemValue ?? '',
          valueType: it.valueType || 'string',
          optionsText: (it.options || []).join(', '),
          recommended: it.recommended ?? '',
          description: it.description ?? '',
          sort: it.sort,
        }
      : {
          itemKey: '',
          itemValue: '',
          valueType: props.categoryKey === 'sysctl' ? 'int' : 'string',
          optionsText: '',
          recommended: '',
          description: '',
        };
  },
);

function useRecommended() {
  if (!form.value.recommended) {
    message.info('该项没有推荐值');
    return;
  }
  form.value.itemValue = form.value.recommended;
}

function onOk() {
  const key = (form.value.itemKey || '').trim();
  if (!key) {
    message.error('参数名不能为空');
    return;
  }
  if (/\s/.test(key) && props.categoryKey !== 'limits') {
    // limits 的参数名形如 `* soft nofile`，本身带空格；其它类别的键不允许空格
    message.error('参数名不能包含空格');
    return;
  }
  if (
    form.value.valueType === 'enum' &&
    !(form.value.optionsText || '').trim()
  ) {
    message.error('值类型为 enum 时必须填写候选值');
    return;
  }
  const options =
    form.value.valueType === 'enum'
      ? (form.value.optionsText || '')
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean)
      : undefined;
  emit('submit', {
    itemKey: key,
    itemValue: form.value.itemValue ?? '',
    valueType: form.value.valueType,
    options,
    recommended: form.value.recommended || undefined,
    description: form.value.description || undefined,
    sort: form.value.sort ?? undefined,
  });
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :title="isEdit ? `编辑配置项 · ${item?.itemKey}` : `新增配置项 · ${meta.label}`"
    :width="620"
    ok-text="保存"
    @cancel="emit('update:open', false)"
    @ok="onOk"
  >
    <div class="space-y-3">
      <div>
        <div class="mb-1 text-sm">参数名</div>
        <Input
          v-model:value="form.itemKey"
          :disabled="isEdit"
          :placeholder="categoryKey === 'limits' ? '如 * soft nofile' : categoryKey === 'sysctl' ? '如 net.core.somaxconn' : '如 MaxAuthTries'"
        />
        <div v-if="isEdit" class="mt-1 text-xs text-gray-500">
          参数名创建后不可修改；如写错请删除后重建。
        </div>
      </div>

      <div>
        <div class="mb-1 flex items-center gap-2 text-sm">
          <span>托管值</span>
          <span class="text-xs text-gray-500">留空 = 不托管（回到发行版默认值）</span>
          <Button class="ml-auto" size="small" type="link" @click="useRecommended">
            用推荐值
          </Button>
        </div>
        <Input
          v-model:value="form.itemValue"
          :placeholder="meta.placeholder"
          allow-clear
        />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="mb-1 text-sm">值类型</div>
          <Select
            v-model:value="form.valueType"
            :options="VALUE_TYPES"
            class="w-full"
          />
        </div>
        <div>
          <div class="mb-1 text-sm">排序</div>
          <InputNumber v-model:value="form.sort" class="w-full" :min="0" />
        </div>
      </div>

      <div v-if="form.valueType === 'enum'">
        <div class="mb-1 text-sm">候选值（英文逗号分隔）</div>
        <Input v-model:value="form.optionsText" placeholder="yes, no" />
      </div>

      <div>
        <div class="mb-1 text-sm">推荐值</div>
        <Input v-model:value="form.recommended" placeholder="可选，用于一键回填与提示" />
      </div>

      <div>
        <div class="mb-1 text-sm">说明</div>
        <Input.TextArea
          v-model:value="form.description"
          :rows="2"
          placeholder="写清用途与风险，便于后续维护者判断"
        />
      </div>

      <Alert
        show-icon
        type="info"
        :message="`改动落在：${meta.scope}`"
        :description="'保存只写入面板数据库，不会立刻改系统；需在列表页点「一键生效」才会落到系统配置。'"
      />
    </div>
  </Modal>
</template>
