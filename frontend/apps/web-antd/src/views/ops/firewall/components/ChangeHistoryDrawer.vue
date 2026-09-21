<script lang="ts" setup>
/**
 * 防火墙变更历史抽屉
 *
 * <p>存在意义：防火墙的每一次写操作都是不可逆的高危动作，光有「操作成功」的提示不够，
 * 必须留下「改了什么、谁改的、能不能撤回来」。本抽屉把三件事放在一起呈现：
 * <ol>
 *   <li><b>变更前后快照对照</b>：两栏并排显示 {@code ufw status numbered} 原文，配合高亮 diff，
 *       一眼看出到底哪条规则消失了。</li>
 *   <li><b>结构化 diff</b>：后端算好的 {@code + 新增 / - 删除 / * 修改} 行，比人肉比对快得多。</li>
 *   <li><b>一键回滚</b>：按快照重放撤销操作；不可回滚（快照缺失或已被后续变更覆盖）的记录
 *       会明确置灰并说明原因，而不是点了才报错。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import { getFirewallChangeDetailApi, getFirewallChangesApi } from '#/api';

import { diffLineClass, fmtTime, opLabel, resultMeta } from '../utils';

defineOptions({ name: 'OpsFirewallChangeDrawer' });

const props = defineProps<{
  open: boolean;
  canRollback?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  /** 请求父组件执行回滚：父组件统一处理权限、提示与刷新 */
  rollback: [string, string];
}>();

const loading = ref(false);
const rows = ref<OpsApi.FirewallChange[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);

const detail = ref<OpsApi.FirewallChange | null>(null);
const detailLoading = ref(false);

const rollbackConfirm = ref('');
const rollbackOpen = ref(false);
const rollbackTarget = ref<OpsApi.FirewallChange | null>(null);
const rollbacking = ref(false);

async function load() {
  loading.value = true;
  try {
    const res = await getFirewallChangesApi({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    });
    rows.value = res.records ?? [];
    total.value = res.total ?? 0;
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    pageNum.value = 1;
    detail.value = null;
    await load();
  },
);

async function openDetail(row: OpsApi.FirewallChange) {
  if (detail.value?.id === row.id) {
    detail.value = null;
    return;
  }
  detailLoading.value = true;
  try {
    detail.value = await getFirewallChangeDetailApi(row.id);
  } finally {
    detailLoading.value = false;
  }
}

/** diff_json 是后端算好的字符串数组（+ 新增 / - 删除 / * 修改） */
const diffLines = computed<string[]>(() => {
  const raw = detail.value?.diffJson;
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return String(raw).split('\n').filter(Boolean);
  }
});

const beforeLines = computed(() =>
  (detail.value?.beforeSnapshot ?? '').split('\n').filter((l) => l.trim()),
);
const afterLines = computed(() =>
  (detail.value?.afterSnapshot ?? '').split('\n').filter((l) => l.trim()),
);

function rollbackable(row: OpsApi.FirewallChange): boolean {
  return row.rollbackable === 1 && row.rolledBack !== 1;
}

function openRollback(row: OpsApi.FirewallChange) {
  rollbackTarget.value = row;
  rollbackConfirm.value = '';
  rollbackOpen.value = true;
}

async function doRollback() {
  const row = rollbackTarget.value;
  if (!row) return;
  if (rollbackConfirm.value.trim().toUpperCase() !== 'ROLLBACK') {
    message.warning('请键入 ROLLBACK 以确认回滚');
    return;
  }
  rollbacking.value = true;
  try {
    emit('rollback', row.id, 'ROLLBACK');
    rollbackOpen.value = false;
  } finally {
    rollbacking.value = false;
  }
}

async function onPageChange(page: number) {
  pageNum.value = page;
  await load();
}

const columns = [
  { dataIndex: 'createdAt', title: '时间', width: 170 },
  { dataIndex: 'operator', title: '操作人', width: 110 },
  { dataIndex: 'op', title: '操作', width: 120 },
  { dataIndex: 'ruleDesc', title: '规则' },
  { dataIndex: 'result', title: '结果', width: 100 },
  { dataIndex: 'actions', title: '操作', width: 170 },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="860"
    title="防火墙变更历史"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <Alert
        class="mb-3"
        message="每次变更都会保存前后快照，可在此比对差异并回滚。已回滚或快照缺失的记录不可再次回滚。"
        show-icon
        type="info"
      />
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: false,
          onChange: onPageChange,
        }"
        :row-key="(r: OpsApi.FirewallChange) => r.id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'createdAt'">
            {{ fmtTime(record.createdAt) }}
          </template>
          <template v-else-if="column.dataIndex === 'op'">
            <Tag>{{ opLabel((record as OpsApi.FirewallChange).op) }}</Tag>
          </template>
          <template v-else-if="column.dataIndex === 'ruleDesc'">
            <span class="break-all text-xs">{{ (record as OpsApi.FirewallChange).ruleDesc }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'result'">
            <Tag :color="resultMeta(record as OpsApi.FirewallChange).color">
              {{ resultMeta(record as OpsApi.FirewallChange).label }}
            </Tag>
            <span v-if="record.errorMsg" class="ml-1 text-xs text-red-500">
              {{ record.errorMsg }}
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'actions'">
            <Space>
              <Button size="small" type="link" @click="openDetail(record as OpsApi.FirewallChange)">
                {{ detail?.id === (record as OpsApi.FirewallChange).id ? '收起' : '查看 diff' }}
              </Button>
              <Button
                v-if="canRollback"
                :disabled="!rollbackable(record as OpsApi.FirewallChange)"
                danger
                size="small"
                type="link"
                @click="openRollback(record as OpsApi.FirewallChange)"
              >
                回滚
              </Button>
            </Space>
          </template>
        </template>
      </Table>

      <div v-if="detail" class="mt-3 rounded border p-3">
        <Spin :spinning="detailLoading">
          <div class="mb-2 text-sm font-medium">变更差异</div>
          <div v-if="diffLines.length" class="mb-3">
            <div
              v-for="(line, i) in diffLines"
              :key="i"
              :class="diffLineClass(line)"
              class="rounded px-2 py-0.5 font-mono text-xs"
            >
              {{ line }}
            </div>
          </div>
          <Empty
            v-else
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
            description="无结构化差异（可能是开关类变更）"
          />

          <div class="mt-3 grid grid-cols-2 gap-3">
            <div>
              <div class="mb-1 text-xs text-gray-500">变更前</div>
              <pre
                class="max-h-[240px] overflow-auto rounded bg-gray-50 p-2 font-mono text-[11px] leading-5"
              >{{ beforeLines.join('\n') || '（无快照）' }}</pre>
            </div>
            <div>
              <div class="mb-1 text-xs text-gray-500">变更后</div>
              <pre
                class="max-h-[240px] overflow-auto rounded bg-gray-50 p-2 font-mono text-[11px] leading-5"
              >{{ afterLines.join('\n') || '（无快照）' }}</pre>
            </div>
          </div>
        </Spin>
      </div>
    </Spin>

    <Modal
      :open="rollbackOpen"
      title="回滚防火墙变更"
      @cancel="rollbackOpen = false"
      @ok="doRollback"
    >
      <Alert
        class="my-3"
        message="回滚会按快照重放撤销操作，属于高危动作：请确认当前网络连通后再执行。"
        show-icon
        type="warning"
      />
      <div class="mb-1 text-sm">
        将回滚：{{ opLabel(rollbackTarget?.op) }} ·
        {{ rollbackTarget?.ruleDesc }}
      </div>
      <div class="mb-1 text-xs text-gray-500">请键入 ROLLBACK 以确认</div>
      <Input v-model:value="rollbackConfirm" placeholder="ROLLBACK" />
    </Modal>
  </Drawer>
</template>

<style scoped>
.diff-add {
  background-color: #f6ffed;
  color: #389e0d;
}
.diff-del {
  background-color: #fff2f0;
  color: #cf1322;
}
.diff-mod {
  background-color: #e6f4ff;
  color: #0958d9;
}
</style>
