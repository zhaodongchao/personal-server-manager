<script lang="ts" setup>
/**
 * 防火墙规则表单（新增 / 「复制为新规则」）
 *
 * <p>四个刻意的设计选择：
 * <ol>
 *   <li><b>目标形态可切换。</b>旧版只有「单端口 + 协议」一种写法，于是端口范围
 *       （{@code 40000:40100/tcp}）和多端口（{@code 80,443/tcp}）规则在页面上既看不懂也删不掉。
 *       这里把 ufw 的四种写法全部暴露出来，规则形态与命令行一一对应。</li>
 *   <li><b>命令原文预览。</b>提交前把将要执行的 {@code ufw ...} 原文显示在表单底部。
 *       防火墙改错就失联，让使用者看到真实命令比任何提示文案都可靠。</li>
 *   <li><b>实时生存线判定。</b>规则一旦覆盖 SSH / 面板端口，立刻变红并要求键入
 *       {@code SSH 22} 这类关键字——与后端 {@code requireGuard} 同一口径，后端仍会再拦一次。</li>
 *   <li><b>备注自动带前缀。</b>面板写入的规则统一加 {@code psm:} 前缀，用于区分「面板建的」
 *       和「人在命令行建的」，删除前不必猜。</li>
 * </ol>
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
  RadioGroup,
  Select,
} from 'ant-design-vue';

import {
  ACTION_OPTIONS,
  PROTOCOL_OPTIONS,
  TARGET_OPTIONS,
  evaluateAddRisk,
  previewCommand,
} from '../utils';

defineOptions({ name: 'OpsFirewallRuleFormModal' });

const props = defineProps<{
  open: boolean;
  /** 传入则用于「复制为新规则」预填 */
  source?: OpsApi.FirewallRule | null;
  /** 生存线信息（SSH / 面板端口），用于实时风险判定 */
  guard?: OpsApi.FirewallGuard | null;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  submit: [OpsApi.FirewallRuleBody];
}>();

interface FormState {
  kind: 'port' | 'range' | 'multi' | 'any';
  port?: number;
  portEnd?: number;
  portsText: string;
  protocol: 'tcp' | 'udp' | 'any';
  action: 'allow' | 'deny' | 'reject' | 'limit';
  source: string;
  comment: string;
  confirm: string;
}

function blank(): FormState {
  return {
    kind: 'port',
    port: undefined,
    portEnd: undefined,
    portsText: '',
    protocol: 'tcp',
    action: 'allow',
    source: '',
    comment: '',
    confirm: '',
  };
}

const form = ref<FormState>(blank());

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    const src = props.source;
    if (src) {
      form.value = {
        ...blank(),
        kind: src.toKind === 'app' ? 'port' : (src.toKind as FormState['kind']) ?? 'port',
        action: src.action ?? 'allow',
        comment: src.comment ?? '',
      };
      // 从 to 反解端口回填，尽量让「复制为新规则」开箱可用
      const nums = (src.to ?? '').replace(/\/(tcp|udp).*$/i, '').match(/\d+/g);
      if (form.value.kind === 'range' && nums && nums.length >= 2) {
        form.value.port = Number(nums[0]);
        form.value.portEnd = Number(nums[1]);
      } else if (form.value.kind === 'multi') {
        form.value.portsText = nums ? nums.join(',') : '';
      } else if (form.value.kind === 'port' && nums?.length) {
        form.value.port = Number(nums[0]);
      }
    } else {
      form.value = blank();
    }
  },
);

/** 来源格式：留空 = 任意来源；支持 IPv4/IPv6 单地址与 CIDR */
const sourceInvalid = computed(() => {
  const v = form.value.source.trim();
  if (!v) return false;
  if (/^(\d{1,3}\.){3}\d{1,3}(\/\d{1,2})?$/.test(v)) {
    return v.includes('/')
      ? Number(v.split('/')[1]) > 32
      : v.split('.').some((x) => Number(x) > 255);
  }
  // IPv6：粗校验（含冒号，且只由十六进制与 : / 组成）
  return !/^[0-9a-f:./]+$/i.test(v);
});

const portsInvalid = computed(() => {
  const s = form.value.portsText.trim();
  if (form.value.kind !== 'multi') return false;
  if (!s) return true;
  return s.split(',').some((x) => {
    const n = Number(x.trim());
    return !Number.isFinite(n) || n < 1 || n > 65_535;
  });
});

/** 组装请求体（校验通过才有意义） */
const body = computed<OpsApi.FirewallRuleBody>(() => {
  const f = form.value;
  const target: OpsApi.FirewallRuleTarget = { kind: f.kind };
  if (f.kind === 'port') target.port = f.port;
  if (f.kind === 'range') {
    target.port = f.port;
    target.portEnd = f.portEnd;
  }
  if (f.kind === 'multi') {
    target.ports = f.portsText
      .split(',')
      .map((x) => Number(x.trim()))
      .filter((n) => Number.isFinite(n));
  }
  return {
    target,
    protocol: f.protocol,
    action: f.action,
    source: f.source.trim() || undefined,
    comment: f.comment.trim() || undefined,
  };
});

const risk = computed(() => evaluateAddRisk(body.value, props.guard));

const command = computed(() => previewCommand(body.value));

/** 目标部分是否填完整 */
const targetMissing = computed(() => {
  const f = form.value;
  if (f.kind === 'port') return !f.port;
  if (f.kind === 'range') return !f.port || !f.portEnd;
  return false;
});

const canSubmit = computed(
  () =>
    !targetMissing.value &&
    !portsInvalid.value &&
    !sourceInvalid.value &&
    (risk.value?.level !== 'danger' ||
      form.value.confirm.trim().toUpperCase() ===
        (risk.value.keyword ?? '').toUpperCase()),
);

function onSubmit() {
  if (!canSubmit.value) return;
  emit('submit', {
    ...body.value,
    confirm: risk.value?.level === 'danger' ? form.value.confirm.trim() : undefined,
  });
}
</script>

<template>
  <Modal
    :ok-button-props="{ disabled: !canSubmit }"
    :open="open"
    :width="560"
    title="防火墙规则"
    @cancel="emit('update:open', false)"
    @ok="onSubmit"
  >
    <Form class="mt-2" layout="vertical">
      <Form.Item label="目标">
        <RadioGroup v-model:value="form.kind" :options="TARGET_OPTIONS" />
      </Form.Item>

      <div v-if="form.kind === 'port'" class="flex items-start gap-2">
        <Form.Item class="flex-1" label="端口">
          <InputNumber
            v-model:value="form.port"
            :max="65535"
            :min="1"
            class="w-full"
            placeholder="如 8080"
          />
        </Form.Item>
      </div>

      <div v-else-if="form.kind === 'range'" class="flex items-start gap-2">
        <Form.Item class="flex-1" label="起始端口">
          <InputNumber
            v-model:value="form.port"
            :max="65535"
            :min="1"
            class="w-full"
          />
        </Form.Item>
        <Form.Item class="flex-1" label="结束端口">
          <InputNumber
            v-model:value="form.portEnd"
            :max="65535"
            :min="1"
            class="w-full"
          />
        </Form.Item>
      </div>

      <Form.Item
        v-else-if="form.kind === 'multi'"
        :validate-status="portsInvalid ? 'error' : ''"
        help="多个端口用英文逗号分隔，如 80,443,8080"
        label="端口列表"
      >
        <Input
          v-model:value="form.portsText"
          placeholder="80,443,8080"
        />
      </Form.Item>

      <div class="flex items-start gap-2">
        <Form.Item class="flex-1" label="协议">
          <Select v-model:value="form.protocol" :options="PROTOCOL_OPTIONS" />
        </Form.Item>
        <Form.Item class="flex-1" label="动作">
          <Select v-model:value="form.action" :options="ACTION_OPTIONS" />
        </Form.Item>
      </div>

      <Form.Item
        :validate-status="sourceInvalid ? 'error' : ''"
        :help="sourceInvalid ? '格式应为 IPv4/IPv6 地址或 CIDR，如 192.168.1.0/24' : ''"
        label="来源"
      >
        <Input
          v-model:value="form.source"
          placeholder="留空表示任意来源（Anywhere）"
        />
      </Form.Item>

      <Form.Item
        help="备注会自动加 psm: 前缀，用于区分面板写入与命令行写入的规则"
        label="备注"
      >
        <Input v-model:value="form.comment" placeholder="如 放行内网监控" />
      </Form.Item>

      <Alert
        v-if="risk"
        :message="risk.text"
        :type="risk.level === 'danger' ? 'error' : 'warning'"
        class="mb-3"
        show-icon
      />
      <Form.Item
        v-if="risk?.level === 'danger'"
        :help="`请键入 ${risk.keyword} 以确认`"
        label="二次确认"
      >
        <Input
          v-model:value="form.confirm"
          :placeholder="risk.keyword"
        />
      </Form.Item>

      <Divider class="my-2" />
      <div class="mb-1 text-xs text-gray-500">将执行</div>
      <pre
        class="overflow-x-auto rounded bg-gray-50 px-3 py-2 text-xs text-gray-700"
      >{{ command }}</pre>
    </Form>
  </Modal>
</template>
