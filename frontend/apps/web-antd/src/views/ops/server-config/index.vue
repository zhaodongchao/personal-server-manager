<script lang="ts" setup>
/**
 * 服务器配置管理页面（运维工具 → 服务器配置）。
 *
 * <p>定位：把「改系统配置」这件高危事情做成可预演、可追溯、可回滚的产品化操作。
 * 页面围绕三个约束设计：
 * <ol>
 *   <li><b>非侵入。</b>只写发行版之外的 drop-in 片段（各类别配置目录下的
 *       {@code 99-serverpanel.conf}），
 *       发行版主配置文件永远不动，因此「停止托管」= 删片段 = 回到原状，无副作用残留。</li>
 *   <li><b>空值即不托管。</b>配置项的托管值为空表示本模块不向系统写这一项，
 *       这是最常被误解的语义，因此列表里把「不托管」显式标出来，而不是留白。</li>
 *   <li><b>先预演再生效。</b>「一键生效」按钮走的是预演弹窗，看到 dry-run 输出与行级 diff
 *       之后才允许提交；L3 类别（sshd）还要键入关键字。</li>
 * </ol>
 *
 * <p>权限：读 {@code ops:config:list}；配置项增删改与生效 {@code ops:config:apply}；
 * 按历史恢复 {@code ops:config:rollback}。前端只做按钮可见性与交互，
 * 规则判定（黑名单、自锁护栏）一律以后端返回的 {@code ruleErrors / applyKeyword} 为准。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { OpsApi, ServerConfigApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Empty,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'ant-design-vue';

import {
  applyServerConfigApi,
  createServerConfigItemApi,
  deleteServerConfigItemApi,
  detectServerConfigApi,
  getHostCapabilityApi,
  getServerConfigCategoriesApi,
  getServerConfigItemsApi,
  unmanageServerConfigApi,
  updateServerConfigItemApi,
} from '#/api';

import HostChannelBanner from '../components/HostChannelBanner.vue';
import ApplyPreviewModal from './components/ApplyPreviewModal.vue';
import ChangeHistoryDrawer from './components/ChangeHistoryDrawer.vue';
import DangerConfirmModal from './components/DangerConfirmModal.vue';
import ItemFormModal from './components/ItemFormModal.vue';
import { categoryMeta, displayValue, riskMeta } from './utils';

defineOptions({ name: 'OpsServerConfig' });

const { hasAccessByCodes } = useAccess();

const canWrite = computed(() => hasAccessByCodes(['ops:config:apply']));
const canRollback = computed(() => hasAccessByCodes(['ops:config:rollback']));

// ==================== 宿主通道 ====================

const capability = ref<OpsApi.HostCapability>();
const capabilityLoading = ref(false);

async function loadCapability() {
  capabilityLoading.value = true;
  try {
    capability.value = await getHostCapabilityApi();
  } catch {
    capability.value = undefined;
  } finally {
    capabilityLoading.value = false;
  }
}

// ==================== 类别 ====================

const categories = ref<ServerConfigApi.Category[]>([]);
const catLoading = ref(false);
const activeKey = ref('');

const activeCategory = computed(() =>
  categories.value.find((c) => c.categoryKey === activeKey.value),
);

const items = ref<ServerConfigApi.Item[]>([]);
const itemLoading = ref(false);

/** 当前类别是否可用（宿主能力缺失时应降级为只读） */
const categoryAvailable = computed(
  () => activeCategory.value?.available !== false,
);

async function loadCategories(keepActive = true) {
  catLoading.value = true;
  try {
    categories.value = await getServerConfigCategoriesApi();
    if (!keepActive || !activeKey.value) {
      activeKey.value = categories.value[0]?.categoryKey || '';
    }
    if (!categories.value.some((c) => c.categoryKey === activeKey.value)) {
      activeKey.value = categories.value[0]?.categoryKey || '';
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '类别加载失败');
  } finally {
    catLoading.value = false;
  }
}

async function reDetect() {
  try {
    categories.value = await detectServerConfigApi();
    message.success('已重新探测宿主能力');
    await loadItems();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '探测失败');
  }
}

async function loadItems() {
  if (!activeKey.value) {
    items.value = [];
    return;
  }
  itemLoading.value = true;
  try {
    items.value = await getServerConfigItemsApi(activeKey.value);
  } catch (error) {
    items.value = [];
    message.error(error instanceof Error ? error.message : '配置项加载失败');
  } finally {
    itemLoading.value = false;
  }
}

function onTabChange(key: string | number) {
  activeKey.value = String(key);
  void loadItems();
}

function refreshAll() {
  void loadCategories();
  void loadItems();
}

// ==================== 配置项 CRUD ====================

const itemModalOpen = ref(false);
const editingItem = ref<null | ServerConfigApi.Item>(null);
const itemSubmitting = ref(false);

function openCreate() {
  editingItem.value = null;
  itemModalOpen.value = true;
}

function openEdit(row: ServerConfigApi.Item) {
  editingItem.value = row;
  itemModalOpen.value = true;
}

async function submitItem(body: ServerConfigApi.ItemBody) {
  itemSubmitting.value = true;
  try {
    if (editingItem.value) {
      await updateServerConfigItemApi(activeKey.value, editingItem.value.itemKey, body);
      message.success('已保存（尚未生效，请点「一键生效」写入系统）');
    } else {
      await createServerConfigItemApi(activeKey.value, body);
      message.success('已新增（尚未生效，请点「一键生效」写入系统）');
    }
    itemModalOpen.value = false;
    await loadItems();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    itemSubmitting.value = false;
  }
}

async function removeItem(row: ServerConfigApi.Item) {
  try {
    await deleteServerConfigItemApi(activeKey.value, row.itemKey);
    message.success(`已删除 ${row.itemKey}`);
    await loadItems();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败');
  }
}

/** 清空托管值 = 让该项回到发行版默认值（不是删除配置项） */
async function clearItemValue(row: ServerConfigApi.Item) {
  try {
    await updateServerConfigItemApi(activeKey.value, row.itemKey, {
      itemKey: row.itemKey,
      itemValue: '',
      valueType: row.valueType,
      options: row.options,
      recommended: row.recommended ?? undefined,
      description: row.description,
      sort: row.sort,
    });
    message.success(`${row.itemKey} 已设为「不托管」，点「一键生效」后系统将回到发行版默认值`);
    await loadItems();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败');
  }
}

// ==================== 一键生效 ====================

const previewOpen = ref(false);
const applying = ref(false);

async function onApply(confirm: string) {
  applying.value = true;
  try {
    const res = await applyServerConfigApi(activeKey.value, confirm || undefined);
    if (res?.applied) {
      message.success(res.message || '配置已生效');
    } else if (res?.rolledBack) {
      message.error(`${res.message || '生效失败'}（已自动回滚）`);
    } else {
      message.error(res?.message || '生效失败');
    }
    previewOpen.value = false;
    refreshAll();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '生效失败');
  } finally {
    applying.value = false;
  }
}

// ==================== 停止托管 ====================

const unmanageOpen = ref(false);
const unmanaging = ref(false);

function onUnmanageClick() {
  const cat = activeCategory.value;
  if (!cat) return;
  if (cat.applyKeyword) {
    // L3 类别走关键字确认弹窗（与生效同一套护栏）
    unmanageOpen.value = true;
    return;
  }
  Modal.confirm({
    title: `停止托管「${cat.name}」`,
    content:
      '将删除本模块写入的 drop-in 片段及其全部备份，使发行版原配置重新生效。'
      + '面板里的配置项会保留，不会丢失录入内容。',
    okText: '确认停止托管',
    okButtonProps: { danger: true },
    async onOk() {
      await doUnmanage();
    },
  });
}

async function doUnmanage() {
  unmanaging.value = true;
  try {
    const res = await unmanageServerConfigApi(
      activeKey.value,
      activeCategory.value?.applyKeyword || undefined,
    );
    if (res?.applied) {
      message.success(res.message || '已停止托管');
    } else {
      message.error(res?.message || '停止托管失败');
    }
    unmanageOpen.value = false;
    refreshAll();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '停止托管失败');
  } finally {
    unmanaging.value = false;
  }
}

// ==================== 变更历史 ====================

const historyOpen = ref(false);

function openHistory() {
  historyOpen.value = true;
}

// ==================== 表格列 ====================

const columns = [
  { dataIndex: 'itemKey', key: 'itemKey', title: '参数名', width: 260 },
  { dataIndex: 'itemValue', key: 'itemValue', title: '托管值', width: 200 },
  { dataIndex: 'effectiveValue', key: 'effectiveValue', title: '当前生效值', width: 160 },
  { dataIndex: 'recommended', key: 'recommended', title: '推荐值', width: 140 },
  { dataIndex: 'description', key: 'description', title: '说明' },
  { key: 'action', title: '操作', width: 170, fixed: 'right' as const },
];

onMounted(() => {
  void loadCapability();
  void loadCategories(false).then(() => loadItems());
});
</script>

<template>
  <Page title="服务器配置" description="内核参数 / 资源限制 / SSH / 时间同步 — 可预演、可追溯、可回滚">
    <HostChannelBanner :capability="capability" :loading="capabilityLoading" @refreshed="(c) => (capability = c)" />

    <Alert class="mb-3" show-icon type="info">
      <template #message>
        本模块只写发行版之外的 drop-in 片段（<span class="font-mono text-xs">99-serverpanel.conf</span>），
        从不改动发行版主配置，因此「停止托管」即可干净还原。
      </template>
      <template #description>
        <div class="text-xs">
          配置项<b>值为空 = 不托管</b>：本模块不会向系统写这一项，系统仍用发行版默认值。
          每次「一键生效」都会先备份、跑权威校验，失败自动回滚，并留下前后全文与行级 diff。
        </div>
      </template>
    </Alert>

    <Spin :spinning="catLoading">
      <Tabs
        :active-key="activeKey"
        type="card"
        @change="onTabChange"
      >
        <Tabs.TabPane v-for="cat in categories" :key="cat.categoryKey">
          <template #tab>
            <span class="inline-flex items-center gap-1">
              {{ categoryMeta(cat.categoryKey).label }}
              <Tag v-if="(cat.managedItemCount || 0) > 0" color="blue">
                {{ cat.managedItemCount }}
              </Tag>
              <Tag v-if="cat.available === false" color="red">不可用</Tag>
            </span>
          </template>
        </Tabs.TabPane>
      </Tabs>
    </Spin>

    <Empty v-if="categories.length === 0 && !catLoading" description="没有可用的配置类别（请检查宿主代理）" />

    <template v-if="activeCategory">
      <div
        class="mb-3 rounded border border-gray-200 p-3 text-xs dark:border-gray-700"
      >
        <div class="mb-1 flex flex-wrap items-center gap-2">
          <Tag :color="riskMeta(activeCategory.riskLevel).color">
            {{ riskMeta(activeCategory.riskLevel).label }}
          </Tag>
          <Tag :color="activeCategory.managed ? 'blue' : 'default'">
            {{ activeCategory.managed ? '已生效（托管片段存在）' : '未生效（无托管片段）' }}
          </Tag>
          <Tag v-if="activeCategory.provider" color="cyan">
            provider：{{ activeCategory.provider }}
          </Tag>
          <span class="text-gray-500">
            配置项 {{ activeCategory.itemCount ?? 0 }} 个，其中已托管
            {{ activeCategory.managedItemCount ?? 0 }} 个
          </span>
        </div>
        <div class="text-gray-500 dark:text-gray-400">
          托管文件：
          <span class="font-mono">{{ activeCategory.managedFile }}</span>
        </div>
        <div v-if="activeCategory.sourceFile" class="text-gray-500 dark:text-gray-400">
          发行版主配置：<span class="font-mono">{{ activeCategory.sourceFile }}</span>
          （本模块不修改它）
        </div>
        <div v-if="activeCategory.applyHint" class="mt-1 text-gray-500 dark:text-gray-400">
          生效说明：{{ activeCategory.applyHint }}
        </div>
        <div v-if="activeCategory.unavailableReason" class="mt-1 text-red-600">
          不可用原因：{{ activeCategory.unavailableReason }}
        </div>
      </div>

      <div class="mb-3 flex flex-wrap items-center gap-2">
        <Button
          v-if="canWrite"
          :disabled="!categoryAvailable"
          type="primary"
          @click="openCreate"
        >
          新增配置项
        </Button>
        <Tooltip title="重新读取系统当前生效值（sysctl -n / sshd -T / limits / timesyncd）">
          <Button :disabled="!categoryAvailable" @click="loadItems">刷新生效值</Button>
        </Tooltip>
        <Tooltip title="重新探测宿主能力（装了宿主代理后无需重启面板）">
          <Button @click="reDetect">重新探测</Button>
        </Tooltip>
        <span class="flex-1" />
        <Button @click="openHistory">变更历史</Button>
        <Button
          v-if="canWrite"
          :disabled="!categoryAvailable"
          danger
          @click="previewOpen = true"
        >
          一键生效
        </Button>
        <Button
          v-if="canWrite"
          :disabled="!categoryAvailable || !activeCategory.managed"
          @click="onUnmanageClick"
        >
          停止托管
        </Button>
      </div>

      <Spin :spinning="itemLoading">
        <Table
          :columns="columns"
          :data-source="items"
          :pagination="false"
          :scroll="{ x: 1000 }"
          row-key="itemKey"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'itemKey'">
              <div class="font-mono text-xs">{{ record.itemKey }}</div>
              <Tag v-if="record.builtin === 0" color="purple">自定义</Tag>
            </template>
            <template v-else-if="column.key === 'itemValue'">
              <Tag v-if="!record.itemValue" color="default">不托管</Tag>
              <span v-else class="font-mono text-xs font-medium">{{ record.itemValue }}</span>
            </template>
            <template v-else-if="column.key === 'effectiveValue'">
              <span class="font-mono text-xs text-gray-500">
                {{ displayValue(record.effectiveValue) }}
              </span>
            </template>
            <template v-else-if="column.key === 'recommended'">
              <span class="font-mono text-xs text-gray-400">
                {{ displayValue(record.recommended) }}
              </span>
            </template>
            <template v-else-if="column.key === 'description'">
              <span class="text-xs text-gray-500">{{ record.description }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <Space v-if="canWrite" :size="4">
                <Button size="small" type="link" @click="openEdit(record)">编辑</Button>
                <Popconfirm
                  v-if="record.itemValue"
                  :title="`将 ${record.itemKey} 设为「不托管」？`"
                  description="保存后需点「一键生效」，系统才会回到该项的发行版默认值。"
                  ok-text="确认"
                  @confirm="clearItemValue(record)"
                >
                  <Button size="small" type="link">清空值</Button>
                </Popconfirm>
                <Popconfirm
                  :title="`删除配置项 ${record.itemKey}？`"
                  description="仅移除面板里的录入，不会改动系统；已写入托管片段的内容需重新生效才会消失。"
                  ok-text="删除"
                  ok-type="danger"
                  @confirm="removeItem(record)"
                >
                  <Button danger size="small" type="link">删除</Button>
                </Popconfirm>
              </Space>
              <span v-else class="text-xs text-gray-400">只读</span>
            </template>
          </template>
        </Table>
        <Empty
          v-if="!itemLoading && items.length === 0"
          description="该类别暂无配置项，点「新增配置项」开始"
        />
      </Spin>
    </template>

    <ItemFormModal
      v-model:open="itemModalOpen"
      :category-key="activeKey"
      :item="editingItem"
      :submitting="itemSubmitting"
      @submit="submitItem"
    />

    <ApplyPreviewModal
      v-model:open="previewOpen"
      :category-key="activeKey"
      :category-name="activeCategory?.name"
      :risk-level="activeCategory?.riskLevel"
      :apply-hint="activeCategory?.applyHint"
      :submitting="applying"
      @apply="onApply"
    />

    <DangerConfirmModal
      v-model:open="unmanageOpen"
      :keyword="activeCategory?.applyKeyword || ''"
      :ok-text="unmanaging ? '执行中…' : '确认停止托管'"
      :extra-lines="[
        `将删除：${activeCategory?.managedFile || ''}`,
        '以及同目录下本模块生成的全部 .psm.bak.* 备份',
      ]"
      :title="`停止托管「${activeCategory?.name || ''}」（高风险）`"
      description="停止托管会删除托管片段并触发服务重载/重启，使发行版原配置重新生效。该类别属高风险类别，请确认仍可登录后再执行。"
      @confirm="doUnmanage"
    />

    <ChangeHistoryDrawer
      v-model:open="historyOpen"
      :categories="categories"
      :can-restore="canRollback"
      :category-key="activeKey || 'all'"
      @restored="refreshAll"
    />
  </Page>
</template>
