<script lang="ts" setup>
/**
 * Nginx 变更历史与回滚抽屉。
 *
 * <p>每次写操作（建站/删站/上游/证书/重载等）都会在 `ops_nginx_change` 落一条快照
 * （before_conf / after_conf / rollback_conf），本抽屉展示历史、对照前后配置，
 * 并支持一键回滚（键入 {@code ROLLBACK} 二次确认）。回滚同样走 `nginx -t` + reload。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { NginxApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Space,
  Spin,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import { getNginxChangeDetailApi, getNginxChangePageApi, rollbackNginxChangeApi } from '#/api';

import { fmtTime, opLabel, resultMeta } from '../utils';

import DangerConfirmModal from './DangerConfirmModal.vue';

defineOptions({ name: 'OpsNginxChangeDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
  canRollback?: boolean;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
  /** 回滚完成后，父组件刷新状态与列表 */
  rolledBack: [];
}>();

const loading = ref(false);
const rows = ref<NginxApi.NginxChange[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);

const detail = ref<NginxApi.NginxChange | null>(null);
const detailLoading = ref(false);

const dangerOpen = ref(false);
const dangerTarget = ref<NginxApi.NginxChange | null>(null);
const rollbacking = ref(false);

async function load() {
  loading.value = true;
  try {
    const res = await getNginxChangePageApi({
      instanceId: props.instanceId,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    });
    rows.value = res?.records ?? [];
    total.value = res?.total ?? 0;
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    pageNum.value = 1;
    detail.value = null;
    void load();
  },
);

async function openDetail(row: NginxApi.NginxChange) {
  if (detail.value?.id === row.id) {
    detail.value = null;
    return;
  }
  detailLoading.value = true;
  try {
    detail.value = await getNginxChangeDetailApi(row.id as string);
  } finally {
    detailLoading.value = false;
  }
}

const beforeLines = computed(() =>
  (detail.value?.beforeConf ?? '').split('\n').filter((l) => l.trim()),
);
const afterLines = computed(() =>
  (detail.value?.afterConf ?? '').split('\n').filter((l) => l.trim()),
);

function rollbackable(row: NginxApi.NginxChange): boolean {
  return row.rolledBack !== 1 && !!row.confPath;
}

function askRollback(row: NginxApi.NginxChange) {
  dangerTarget.value = row;
  dangerOpen.value = true;
}

async function doRollback() {
  const row = dangerTarget.value;
  if (!row) return;
  rollbacking.value = true;
  try {
    const res = await rollbackNginxChangeApi(row.id as string, 'ROLLBACK');
    message.success(res?.message ?? '回滚成功');
    detail.value = null;
    await load();
    emit('rolledBack');
  } finally {
    rollbacking.value = false;
  }
}

async function onPageChange(page: number) {
  pageNum.value = page;
  await load();
}

const columns = [
  { dataIndex: 'createdAt', title: '时间', width: 160 },
  { dataIndex: 'operator', title: '操作人', width: 110 },
  { dataIndex: 'op', title: '操作', width: 110 },
  { dataIndex: 'target', title: '对象', width: 120 },
  { dataIndex: 'result', title: '结果', width: 90 },
  { dataIndex: 'actions', title: '操作', width: 150 },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="860"
    title="Nginx 变更历史"
    @close="emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <Alert
        class="mb-3"
        message="每次配置变更都会保存前后快照，可在此对照差异并回滚。回滚会重新执行 nginx -t 校验并 reload。"
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
        :row-key="(r: NginxApi.NginxChange) => r.id as string"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'createdAt'">
            {{ fmtTime((record as NginxApi.NginxChange).createdAt) }}
          </template>
          <template v-else-if="column.dataIndex === 'operator'">
            {{ (record as NginxApi.NginxChange).operator || '-' }}
          </template>
          <template v-else-if="column.dataIndex === 'op'">
            <Tag>{{ opLabel((record as NginxApi.NginxChange).op) }}</Tag>
          </template>
          <template v-else-if="column.dataIndex === 'target'">
            <span class="text-xs">
              {{ (record as NginxApi.NginxChange).targetType || '-' }}
              <span v-if="(record as NginxApi.NginxChange).targetId">
                #{{ (record as NginxApi.NginxChange).targetId }}
              </span>
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'result'">
            <Tag :color="resultMeta(record as NginxApi.NginxChange).color">
              {{ resultMeta(record as NginxApi.NginxChange).label }}
            </Tag>
          </template>
          <template v-else-if="column.dataIndex === 'actions'">
            <Space :size="0">
              <Button size="small" type="link" @click="openDetail(record as NginxApi.NginxChange)">
                {{ detail?.id === (record as NginxApi.NginxChange).id ? '收起' : '对照' }}
              </Button>
              <Button
                v-if="canRollback"
                :disabled="!rollbackable(record as NginxApi.NginxChange)"
                danger
                size="small"
                type="link"
                @click="askRollback(record as NginxApi.NginxChange)"
              >
                回滚
              </Button>
            </Space>
          </template>
        </template>
      </Table>

      <div v-if="detail" class="mt-3 rounded border p-3">
        <Spin :spinning="detailLoading">
          <div class="mb-2 text-sm font-medium">配置对照</div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <div class="mb-1 text-xs text-gray-500">变更前</div>
              <pre
                class="max-h-[300px] overflow-auto rounded bg-gray-50 p-2 font-mono text-[11px] leading-5 dark:bg-gray-900"
              >{{ beforeLines.join('\n') || '（无快照 / 新建）' }}</pre>
            </div>
            <div>
              <div class="mb-1 text-xs text-gray-500">变更后</div>
              <pre
                class="max-h-[300px] overflow-auto rounded bg-gray-50 p-2 font-mono text-[11px] leading-5 dark:bg-gray-900"
              >{{ afterLines.join('\n') || '（无快照 / 已删除）' }}</pre>
            </div>
          </div>
          <div v-if="detail.confPath" class="mt-2 text-xs text-gray-400">
            配置文件：{{ detail.confPath }}
          </div>
        </Spin>
      </div>
    </Spin>

    <DangerConfirmModal
      v-model:open="dangerOpen"
      description="回滚 nginx 配置属于高危操作，将按快照恢复该配置文件并 reload。"
      :keyword="'ROLLBACK'"
      title="回滚 nginx 变更"
      @confirm="doRollback"
    />
  </Drawer>
</template>
