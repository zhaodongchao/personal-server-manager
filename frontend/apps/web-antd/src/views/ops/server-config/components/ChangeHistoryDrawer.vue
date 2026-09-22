<script lang="ts" setup>
/**
 * 服务器配置变更历史抽屉。
 *
 * <p>设计意图：本模块的每次破坏性动作（一键生效 / 停止托管 / 按历史恢复）都会留下
 * 一条记录，且记录里带<b>前后全文 + 行级 diff + 校验输出 + 生效输出 + 配置项快照</b>。
 * 抽屉提供两种视图：
 * <ul>
 *   <li>列表视图 —— 时间 / 操作 / 结果 / 操作人 / 耗时，按类别过滤或跨类别总览；</li>
 *   <li>详情视图 —— 看清那次变更到底改了什么，并可直接「按历史恢复」（回到变更前 / 变更后）。</li>
 * </ul>
 *
 * <p>不做嵌套抽屉：详情在同一个抽屉里切换视图，避免 z-index 与焦点管理的常见坑。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { ServerConfigApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Drawer,
  Empty,
  Input,
  Pagination,
  Radio,
  Space,
  Spin,
  Table,
  Tag,
  message,
} from 'ant-design-vue';

import {
  getServerConfigChangeDetailApi,
  getServerConfigChangePageApi,
  restoreServerConfigApi,
} from '#/api';

import {
  categoryMeta,
  formatDuration,
  opMeta,
  parseDiff,
  resultMeta,
} from '../utils';

defineOptions({ name: 'OpsServerConfigChangeHistoryDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    /** 类别键；`all` 或留空 = 跨类别总览 */
    categoryKey?: string;
    /** 类别列表，用于取名称与 L3 关键字 */
    categories?: ServerConfigApi.Category[];
    /** 是否允许恢复 */
    canRestore?: boolean;
  }>(),
  { categoryKey: 'all', categories: () => [], canRestore: true },
);

const emit = defineEmits<{
  'update:open': [boolean];
  /** 恢复成功后通知父组件刷新配置项与类别状态 */
  restored: [];
}>();

const loading = ref(false);
const records = ref<ServerConfigApi.Change[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(20);
const scope = ref('all');

const detail = ref<ServerConfigApi.Change>();
const detailLoading = ref(false);
const restoreTarget = ref<'after' | 'before'>('before');
const restoreKeyword = ref('');
const restoring = ref(false);

const columns = [
  { dataIndex: 'createdAt', key: 'createdAt', title: '时间', width: 170 },
  { dataIndex: 'categoryKey', key: 'categoryKey', title: '类别', width: 110 },
  { dataIndex: 'op', key: 'op', title: '操作', width: 110 },
  { dataIndex: 'result', key: 'result', title: '结果', width: 110 },
  { dataIndex: 'operator', key: 'operator', title: '操作人', width: 110 },
  { dataIndex: 'operatorIp', key: 'operatorIp', title: '来源 IP', width: 130 },
  { dataIndex: 'durationMs', key: 'durationMs', title: '耗时', width: 90 },
  { key: 'action', title: '操作', width: 90, fixed: 'right' as const },
];

const scopeOptions = computed(() => [
  { label: '全部类别', value: 'all' },
  ...props.categories.map((c) => ({
    label: categoryMeta(c.categoryKey).label,
    value: c.categoryKey,
  })),
]);

/** 详情里被恢复记录所属类别的 L3 关键字（非 L3 为空） */
const detailKeyword = computed(() => {
  const key = detail.value?.categoryKey;
  if (!key) return '';
  return (
    props.categories.find((c) => c.categoryKey === key)?.applyKeyword || ''
  );
});

const diffLines = computed(() => parseDiff(detail.value?.diff));

const detailChangedItems = computed(() => {
  const before = new Map(
    (detail.value?.beforeItems || []).map((i) => [i.itemKey, i.itemValue ?? '']),
  );
  return (detail.value?.afterItems || [])
    .map((i) => ({
      itemKey: i.itemKey,
      after: i.itemValue ?? '',
      before: before.get(i.itemKey) ?? '',
    }))
    .filter((row) => row.before !== row.after);
});

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    detail.value = undefined;
    // 默认「全部类别」而不是当前页签：变更历史是全局审计轨迹，
    // 若默认按页签过滤，从「内核参数」进来会看到空列表（而实际上其它类别有大量记录），
    // 很容易被误判为「功能没生效」。当前类别就在旁边的按钮组里，一次点击即可切回。
    scope.value = 'all';
    pageNum.value = 1;
    void load();
  },
);

async function load() {
  loading.value = true;
  try {
    const res = await getServerConfigChangePageApi({
      categoryKey: scope.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    });
    records.value = res?.records || [];
    total.value = res?.total || 0;
  } catch (error) {
    records.value = [];
    total.value = 0;
    message.error(error instanceof Error ? error.message : '历史加载失败');
  } finally {
    loading.value = false;
  }
}

function onScopeChange() {
  pageNum.value = 1;
  void load();
}

/** 类别范围切换（antd Radio.Group 的 change 事件负载） */
function onScopeRadioChange(e: { target: { value: string } }) {
  scope.value = e.target.value;
  onScopeChange();
}

function onPageChange(page: number, size: number) {
  pageNum.value = page;
  pageSize.value = size;
  void load();
}

async function openDetail(row: ServerConfigApi.Change) {
  detailLoading.value = true;
  restoreTarget.value = 'before';
  restoreKeyword.value = '';
  try {
    detail.value = await getServerConfigChangeDetailApi(row.id);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

/** 当前查看的记录是否还可以恢复（失败/回滚的记录没有可恢复的落点意义有限，这里都允许，由后端把关） */
async function doRestore() {
  if (!detail.value) return;
  if (detailKeyword.value && restoreKeyword.value.trim() !== detailKeyword.value) {
    message.error(`确认关键字不匹配，请输入 ${detailKeyword.value}`);
    return;
  }
  restoring.value = true;
  try {
    const res = await restoreServerConfigApi(
      detail.value.id,
      restoreTarget.value,
      restoreKeyword.value.trim() || undefined,
    );
    if (res?.applied) {
      message.success(res.message || '已按历史恢复并生效');
    } else {
      message.error(res?.message || '恢复失败');
    }
    await load();
    emit('restored');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '恢复失败');
  } finally {
    restoring.value = false;
  }
}
</script>

<template>
  <Drawer
    :open="open"
    :title="detail ? '变更详情' : '变更历史'"
    :width="1000"
    @close="emit('update:open', false)"
  >
    <!-- ==================== 列表视图 ==================== -->
    <template v-if="!detail">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Radio.Group
          :options="scopeOptions"
          :value="scope"
          option-type="button"
          size="small"
          @change="onScopeRadioChange"
        />
        <Button class="ml-auto" size="small" @click="load">刷新</Button>
      </div>

      <Spin :spinning="loading">
        <Table
          :columns="columns"
          :data-source="records"
          :pagination="false"
          :scroll="{ x: 880 }"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'categoryKey'">
              <Tag :color="categoryMeta(record.categoryKey).color">
                {{ categoryMeta(record.categoryKey).label }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'op'">
              <Tag :color="opMeta(record.op).color">{{ opMeta(record.op).label }}</Tag>
            </template>
            <template v-else-if="column.key === 'result'">
              <Tag :color="resultMeta(record.result).color">
                {{ resultMeta(record.result).label }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'durationMs'">
              {{ formatDuration(record.durationMs) }}
            </template>
            <template v-else-if="column.key === 'createdAt'">
              <span class="text-xs">{{ String(record.createdAt || '').replace('T', ' ') }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <Button size="small" type="link" @click="openDetail(record)">
                详情
              </Button>
            </template>
          </template>
        </Table>

        <Empty v-if="!loading && records.length === 0" description="暂无变更记录" />

        <div class="mt-3 flex justify-end">
          <Pagination
            :current="pageNum"
            :page-size="pageSize"
            :show-size-changer="true"
            :total="total"
            size="small"
            @change="onPageChange"
            @show-size-change="onPageChange"
          />
        </div>
      </Spin>
    </template>

    <!-- ==================== 详情视图 ==================== -->
    <Spin v-else :spinning="detailLoading">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Button size="small" @click="detail = undefined">← 返回列表</Button>
        <Tag :color="categoryMeta(detail.categoryKey).color">
          {{ detail.categoryName || categoryMeta(detail.categoryKey).label }}
        </Tag>
        <Tag :color="opMeta(detail.op).color">{{ opMeta(detail.op).label }}</Tag>
        <Tag :color="resultMeta(detail.result).color">
          {{ resultMeta(detail.result).label }}
        </Tag>
        <span class="text-xs text-gray-500">
          {{ String(detail.createdAt || '').replace('T', ' ') }} ·
          {{ detail.operator || '-' }}（{{ detail.operatorIp || '-' }}）·
          {{ formatDuration(detail.durationMs) }}
        </span>
      </div>

      <Alert
        v-if="detail.errorMsg"
        class="mb-2"
        show-icon
        type="error"
        :message="detail.errorMsg"
      />

      <div v-if="detail.backupPath" class="mb-2 font-mono text-xs text-gray-500">
        备份：{{ detail.backupPath }}
      </div>

      <div class="mb-1 text-sm">行级 diff</div>
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
        <div v-if="diffLines.length === 0" class="text-gray-400">（无差异内容）</div>
      </div>

      <div v-if="detailChangedItems.length > 0" class="mb-3">
        <div class="mb-1 text-sm">配置项变化</div>
        <Table
          :columns="[
            { dataIndex: 'itemKey', key: 'itemKey', title: '参数名' },
            { dataIndex: 'before', key: 'before', title: '变更前' },
            { dataIndex: 'after', key: 'after', title: '变更后' },
          ]"
          :data-source="detailChangedItems"
          :pagination="false"
          row-key="itemKey"
          size="small"
        />
      </div>

      <div class="mb-3 grid grid-cols-2 gap-3">
        <div>
          <div class="mb-1 text-sm">变更前托管内容</div>
          <pre
            class="max-h-40 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
          >{{ detail.beforeContent || '（此前未托管）' }}</pre>
        </div>
        <div>
          <div class="mb-1 text-sm">变更后托管内容</div>
          <pre
            class="max-h-40 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
          >{{ detail.afterContent || '（已停止托管）' }}</pre>
        </div>
      </div>

      <div v-if="detail.validateOutput" class="mb-3">
        <div class="mb-1 text-sm">校验输出</div>
        <pre
          class="max-h-32 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
        >{{ detail.validateOutput }}</pre>
      </div>

      <div v-if="detail.applyOutput" class="mb-3">
        <div class="mb-1 text-sm">生效输出 / 宿主阶段明细</div>
        <pre
          class="max-h-40 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-5 dark:bg-white/5"
        >{{ detail.applyOutput }}</pre>
      </div>

      <div v-if="canRestore" class="mt-4 rounded border border-orange-200 p-3 dark:border-orange-900">
        <div class="mb-2 text-sm font-medium">按历史一键恢复</div>
        <Alert
          class="mb-2"
          show-icon
          type="warning"
          message="恢复会重新走一遍「备份 → 原子写 → 权威校验 → 生效」链，任一步失败自动回滚。"
        />
        <Space direction="vertical" class="w-full">
          <Radio.Group v-model:value="restoreTarget" size="small">
            <Radio value="before">回到本次变更之前</Radio>
            <Radio value="after">回到本次变更之后</Radio>
          </Radio.Group>
          <template v-if="detailKeyword">
            <div class="text-xs text-gray-600 dark:text-gray-300">
              高风险类别，请输入
              <span class="font-mono font-semibold text-red-600">{{ detailKeyword }}</span>
              确认：
            </div>
            <Input
              v-model:value="restoreKeyword"
              :placeholder="detailKeyword"
              allow-clear
            />
          </template>
          <Button :loading="restoring" danger type="primary" @click="doRestore">
            执行恢复
          </Button>
        </Space>
      </div>
    </Spin>
  </Drawer>
</template>
