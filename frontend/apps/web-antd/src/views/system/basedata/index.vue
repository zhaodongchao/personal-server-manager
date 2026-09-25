<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';
import type { BaseDataApi } from '#/api';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Button,
  Card,
  Input,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Select,
  Table,
  TabPane,
  Tabs,
  Tag,
  Tree,
} from 'ant-design-vue';

import {
  createBankBinApi,
  createPhoneSegmentApi,
  deleteBankBinApi,
  deletePhoneSegmentApi,
  getBankBinPageApi,
  getBasedataSyncLogApi,
  getBasedataSyncStatusApi,
  getPhoneSegmentPageApi,
  getRegionPageApi,
  getRegionTreeApi,
  triggerBasedataSyncApi,
  updateBankBinApi,
  updatePhoneSegmentApi,
} from '#/api';

defineOptions({ name: 'SystemBasedata' });

const { hasAccessByCodes } = useAccess();
const canSync = hasAccessByCodes(['system:basedata:sync']);
const canEdit = hasAccessByCodes(['system:basedata:edit']);

const activeTab = ref('region');

// ==================== 同步状态卡 ====================

const status = ref<BaseDataApi.SyncStatus>();
const triggering = reactive<Record<string, boolean>>({ region: false, phone: false, bin: false });
let pollTimer: null | ReturnType<typeof setInterval> = null;
let pollCount = 0;

const typeNames: Record<string, string> = {
  region: '行政区划',
  phone: '手机号段',
  bin: '银行卡 BIN',
};

const statusTypes = computed(() => status.value?.types ?? []);

async function loadStatus() {
  status.value = await getBasedataSyncStatusApi();
  // 任一类型同步中则继续轮询（2s 间隔，最多 60 次）
  const anyRunning = statusTypes.value.some((item) => item.running);
  if (anyRunning && !pollTimer && pollCount < 60) {
    pollTimer = setInterval(async () => {
      pollCount++;
      await loadStatus();
      if (!statusTypes.value.some((item) => item.running)) {
        stopPoll();
        message.success('同步已结束');
        loadLog();
      }
    }, 2000);
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
    pollCount = 0;
  }
}

async function triggerSync(type: 'bin' | 'phone' | 'region') {
  triggering[type] = true;
  try {
    await triggerBasedataSyncApi(type);
    message.success('同步已开始');
    await loadStatus();
  } finally {
    triggering[type] = false;
  }
}

function formatTime(value: null | string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '从未同步';
}

// ==================== 行政区划 ====================

interface RegionTreeNode {
  children?: RegionTreeNode[];
  isLeaf?: boolean;
  key: string;
  loading?: boolean;
  name: string;
  status: number;
  title?: string;
}

const regionTreeData = ref<RegionTreeNode[]>([]);
const regionSearchMode = ref(false);
const regionKeyword = ref('');
const regionLevel = ref<number>();
const regionPage = reactive({ pageNum: 1, pageSize: 10, total: 0 });
const regionRows = ref<BaseDataApi.RegionRow[]>([]);

const regionColumns: TableColumnsType = [
  { title: '区划代码', dataIndex: 'code', width: 120 },
  { title: '名称', dataIndex: 'name', minWidth: 180 },
  { title: '层级', dataIndex: 'level', width: 130 },
  { title: '状态', dataIndex: 'status', width: 90 },
];

async function loadRegionRoot() {
  const nodes = await getRegionTreeApi();
  regionTreeData.value = nodes.map(toTreeNode);
}

function toTreeNode(node: BaseDataApi.RegionNode): RegionTreeNode {
  return {
    key: node.code,
    name: node.name,
    status: node.status,
    isLeaf: node.leaf,
    children: node.leaf ? undefined : [],
  };
}

/** 展开：未加载过子节点的非叶子节点现取下一级 */
async function onRegionExpand(_keys: (number | string)[], info: { expanded: boolean; node: { key: number | string } }) {
  if (!info.expanded) return;
  const target = findNode(regionTreeData.value, String(info.node.key));
  if (!target || target.isLeaf || (target.children && target.children.length > 0)) return;
  const nodes = await getRegionTreeApi(String(info.node.key));
  target.children = nodes.map(toTreeNode);
  regionTreeData.value = [...regionTreeData.value];
}

function findNode(nodes: RegionTreeNode[], key: string): RegionTreeNode | undefined {
  for (const node of nodes) {
    if (node.key === key) return node;
    const hit = node.children ? findNode(node.children, key) : undefined;
    if (hit) return hit;
  }
  return undefined;
}

function renderTitle(node: RegionTreeNode) {
  return `${node.name}（${node.key}）${node.status === 0 ? '【已撤销】' : ''}`;
}

async function searchRegion() {
  regionSearchMode.value = true;
  const res = await getRegionPageApi({
    keyword: regionKeyword.value || undefined,
    level: regionLevel.value,
    pageNum: regionPage.pageNum,
    pageSize: regionPage.pageSize,
  });
  regionRows.value = res.records ?? [];
  regionPage.total = res.total ?? 0;
}

async function backToTree() {
  regionSearchMode.value = false;
  await loadRegionRoot();
}

function levelText(level: number) {
  return ['省', '市', '区县', '乡镇街道'][level - 1] ?? `L${level}`;
}

// ==================== 手机号段 ====================

const phoneRows = ref<BaseDataApi.PhoneSegment[]>([]);
const phonePage = reactive({ pageNum: 1, pageSize: 10, total: 0 });
const phoneOperator = ref<string>();
const phoneKeyword = ref('');
const phoneLoading = ref(false);

const phoneColumns: TableColumnsType = [
  { title: '号段前缀', dataIndex: 'prefix', width: 120 },
  { title: '运营商', dataIndex: 'operator', width: 120 },
  { title: '卡类型', dataIndex: 'segType', width: 120 },
  { title: '备注', dataIndex: 'note' },
  { title: '操作', dataIndex: 'action', width: 140 },
];

async function loadPhone() {
  phoneLoading.value = true;
  try {
    const res = await getPhoneSegmentPageApi({
      operator: phoneOperator.value || undefined,
      keyword: phoneKeyword.value || undefined,
      pageNum: phonePage.pageNum,
      pageSize: phonePage.pageSize,
    });
    phoneRows.value = res.records ?? [];
    phonePage.total = res.total ?? 0;
  } finally {
    phoneLoading.value = false;
  }
}

function segTypeText(type: number) {
  return type === 2 ? '虚拟运营商' : type === 3 ? '物联卡' : '基础运营商';
}

// ---- 号段编辑 Modal ----

const phoneModal = ref(false);
const phoneEditing = ref(false);
const phoneForm = reactive<BaseDataApi.PhoneSegmentBody>({
  prefix: '',
  operator: '移动',
  segType: 1,
  note: '',
});

function openPhoneCreate() {
  phoneEditing.value = false;
  Object.assign(phoneForm, { id: undefined, prefix: '', operator: '移动', segType: 1, note: '' });
  phoneModal.value = true;
}

function openPhoneEdit(row: BaseDataApi.PhoneSegment) {
  phoneEditing.value = true;
  Object.assign(phoneForm, {
    id: row.id,
    prefix: row.prefix,
    operator: row.operator,
    segType: row.segType,
    note: row.note ?? '',
  });
  phoneModal.value = true;
}

async function submitPhone() {
  if (!/^\d{3,4}$/.test(phoneForm.prefix)) {
    message.warning('号段前缀应为 3-4 位数字');
    return;
  }
  if (phoneEditing.value) {
    await updatePhoneSegmentApi({ ...phoneForm });
    message.success('修改成功');
  } else {
    await createPhoneSegmentApi({ ...phoneForm });
    message.success('新增成功');
  }
  phoneModal.value = false;
  await loadPhone();
}

async function removePhone(row: BaseDataApi.PhoneSegment) {
  await deletePhoneSegmentApi(row.id);
  message.success('删除成功');
  await loadPhone();
}

// ==================== 银行卡 BIN ====================

const binRows = ref<BaseDataApi.BankBin[]>([]);
const binPage = reactive({ pageNum: 1, pageSize: 10, total: 0 });
const binBankName = ref<string>();
const binKeyword = ref('');
const binLoading = ref(false);

const binColumns: TableColumnsType = [
  { title: 'BIN', dataIndex: 'bin', width: 120 },
  { title: '发卡行', dataIndex: 'bankName', width: 200 },
  { title: '简称', dataIndex: 'bankShort', width: 100 },
  { title: '卡种', dataIndex: 'cardType', width: 130 },
  { title: '标准卡长', dataIndex: 'cardLen', width: 100 },
  { title: '备注', dataIndex: 'note' },
  { title: '操作', dataIndex: 'action', width: 140 },
];

const bankOptions = computed(() => {
  const names = new Set(binRows.value.map((row) => row.bankName));
  return [...names].map((name) => ({ label: name, value: name }));
});

async function loadBin() {
  binLoading.value = true;
  try {
    const res = await getBankBinPageApi({
      bankName: binBankName.value || undefined,
      keyword: binKeyword.value || undefined,
      pageNum: binPage.pageNum,
      pageSize: binPage.pageSize,
    });
    binRows.value = res.records ?? [];
    binPage.total = res.total ?? 0;
  } finally {
    binLoading.value = false;
  }
}

function cardTypeText(type: number) {
  return type === 2 ? '贷记卡' : type === 3 ? '准贷记卡' : '借记卡';
}

// ---- BIN 编辑 Modal ----

const binModal = ref(false);
const binEditing = ref(false);
const binForm = reactive<BaseDataApi.BankBinBody>({
  bin: '',
  bankName: '',
  bankShort: '',
  cardType: 1,
  cardLen: 19,
  note: '',
});

function openBinCreate() {
  binEditing.value = false;
  Object.assign(binForm, { id: undefined, bin: '', bankName: '', bankShort: '', cardType: 1, cardLen: 19, note: '' });
  binModal.value = true;
}

function openBinEdit(row: BaseDataApi.BankBin) {
  binEditing.value = true;
  Object.assign(binForm, {
    id: row.id,
    bin: row.bin,
    bankName: row.bankName,
    bankShort: row.bankShort ?? '',
    cardType: row.cardType,
    cardLen: row.cardLen,
    note: row.note ?? '',
  });
  binModal.value = true;
}

async function submitBin() {
  if (!/^\d{6,10}$/.test(binForm.bin)) {
    message.warning('BIN 应为 6-10 位数字');
    return;
  }
  if (!binForm.bankName.trim()) {
    message.warning('发卡行名称不能为空');
    return;
  }
  if (binEditing.value) {
    await updateBankBinApi({ ...binForm });
    message.success('修改成功');
  } else {
    await createBankBinApi({ ...binForm });
    message.success('新增成功');
  }
  binModal.value = false;
  await loadBin();
}

async function removeBin(row: BaseDataApi.BankBin) {
  await deleteBankBinApi(row.id);
  message.success('删除成功');
  await loadBin();
}

// ==================== 同步日志 ====================

const logRows = ref<BaseDataApi.SyncLog[]>([]);
const logPage = reactive({ pageNum: 1, pageSize: 10, total: 0 });
const logType = ref<string>();

const logColumns: TableColumnsType = [
  { title: '类型', dataIndex: 'dataType', width: 100 },
  { title: '触发', dataIndex: 'triggerType', width: 80 },
  { title: '状态', dataIndex: 'status', width: 90 },
  { title: '总行数', dataIndex: 'rowsTotal', width: 90 },
  { title: '新增', dataIndex: 'rowsInserted', width: 80 },
  { title: '更新', dataIndex: 'rowsUpdated', width: 80 },
  { title: '软删', dataIndex: 'rowsDisabled', width: 80 },
  { title: '摘要', dataIndex: 'message' },
  { title: '开始时间', dataIndex: 'startedAt', width: 160 },
];

async function loadLog() {
  const res = await getBasedataSyncLogApi({
    dataType: logType.value || undefined,
    pageNum: logPage.pageNum,
    pageSize: logPage.pageSize,
  });
  logRows.value = res.records ?? [];
  logPage.total = res.total ?? 0;
}

function logStatusTag(status: number) {
  return status === 0
    ? { color: 'blue', text: '进行中' }
    : status === 1
      ? { color: 'green', text: '成功' }
      : { color: 'red', text: '失败' };
}

function formatCell(value: unknown) {
  return value === null || value === undefined ? '-' : String(value);
}

onMounted(async () => {
  await loadStatus();
  await loadRegionRoot();
  await loadPhone();
  await loadBin();
  await loadLog();
});
</script>

<template>
  <Page title="基础数据">
    <!-- 状态卡 -->
    <div class="mb-4 grid grid-cols-1 gap-3 md:grid-cols-3">
      <Card
        v-for="item in statusTypes"
        :key="item.dataType"
        size="small"
        :class="{ 'border-orange-400 bg-orange-50/50': item.dataType === 'region' && !item.ready }"
      >
        <div class="flex items-center justify-between">
          <div>
            <div class="font-medium">{{ typeNames[item.dataType] ?? item.dataType }}</div>
            <div class="mt-1 text-sm opacity-70">
              {{ item.rows }} 行 · 最近同步：{{ formatTime(item.lastSyncAt) }}
            </div>
            <div class="mt-1 text-xs">
              <Tag v-if="item.running" color="blue">同步中</Tag>
              <Tag v-else-if="item.lastStatus === 1" color="green">上次成功</Tag>
              <Tag v-else-if="item.lastStatus === 2" color="red">上次失败</Tag>
              <Tag v-else>未同步</Tag>
            </div>
            <div v-if="item.dataType === 'region' && !item.ready" class="mt-1 text-xs text-orange-500">
              尚未同步，点击「立即同步」初始化
            </div>
            <div v-if="item.lastMessage && item.lastStatus === 2" class="mt-1 max-w-[320px] truncate text-xs text-red-500" :title="item.lastMessage">
              {{ item.lastMessage }}
            </div>
          </div>
          <Button
            v-if="canSync"
            :disabled="item.running"
            :loading="triggering[item.dataType]"
            size="small"
            type="primary"
            @click="triggerSync(item.dataType as 'bin' | 'phone' | 'region')"
          >
            立即同步
          </Button>
        </div>
      </Card>
    </div>

    <Tabs v-model:activeKey="activeTab">
      <!-- Tab1 行政区划 -->
      <TabPane key="region" tab="行政区划">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Input
            v-model:value="regionKeyword"
            class="max-w-[240px]"
            placeholder="区划代码或名称"
            allow-clear
            @pressEnter="searchRegion"
          />
          <Select
            v-model:value="regionLevel"
            class="w-[140px]"
            :options="[
              { label: '省级', value: 1 },
              { label: '市级', value: 2 },
              { label: '区县级', value: 3 },
              { label: '乡镇街道', value: 4 },
            ]"
            placeholder="层级"
            allow-clear
          />
          <Button type="primary" @click="searchRegion">检索</Button>
          <Button v-if="regionSearchMode" @click="backToTree">返回树视图</Button>
        </div>

        <Tree
          v-if="!regionSearchMode"
          :tree-data="regionTreeData"
          :height="520"
          @expand="onRegionExpand"
        >
          <template #title="{ dataRef }">
            {{ renderTitle(dataRef) }}
          </template>
        </Tree>

        <template v-else>
          <Table
            :columns="regionColumns"
            :data-source="regionRows"
            :pagination="false"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'level'">
                {{ levelText((record as BaseDataApi.RegionRow).level) }}
              </template>
              <template v-else-if="column.dataIndex === 'status'">
                <Tag :color="(record as BaseDataApi.RegionRow).status === 1 ? 'green' : 'default'">
                  {{ (record as BaseDataApi.RegionRow).status === 1 ? '启用' : '停用' }}
                </Tag>
              </template>
            </template>
          </Table>
          <div class="mt-3 flex justify-end">
            <Pagination
              v-model:current="regionPage.pageNum"
              v-model:page-size="regionPage.pageSize"
              :total="regionPage.total"
              show-size-changer
              @change="searchRegion"
            />
          </div>
        </template>
      </TabPane>

      <!-- Tab2 手机号段 -->
      <TabPane key="phone" tab="手机号段">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Input
            v-model:value="phoneKeyword"
            class="max-w-[200px]"
            placeholder="号段前缀"
            allow-clear
            @pressEnter="loadPhone"
          />
          <Select
            v-model:value="phoneOperator"
            class="w-[140px]"
            :options="[
              { label: '移动', value: '移动' },
              { label: '联通', value: '联通' },
              { label: '电信', value: '电信' },
              { label: '广电', value: '广电' },
              { label: '虚拟运营商', value: '虚拟运营商' },
            ]"
            placeholder="运营商"
            allow-clear
          />
          <Button type="primary" @click="loadPhone">查询</Button>
          <Button v-if="canEdit" type="primary" ghost @click="openPhoneCreate">新增号段</Button>
        </div>
        <Table
          :columns="phoneColumns"
          :data-source="phoneRows"
          :loading="phoneLoading"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'segType'">
              {{ segTypeText((record as BaseDataApi.PhoneSegment).segType) }}
            </template>
            <template v-else-if="column.dataIndex === 'note'">
              {{ (record as BaseDataApi.PhoneSegment).note || '-' }}
            </template>
            <template v-else-if="column.dataIndex === 'action' && canEdit">
              <Button size="small" type="link" @click="openPhoneEdit(record as BaseDataApi.PhoneSegment)">编辑</Button>
              <Popconfirm title="确定删除该号段？" @confirm="removePhone(record as BaseDataApi.PhoneSegment)">
                <Button danger size="small" type="link">删除</Button>
              </Popconfirm>
            </template>
          </template>
        </Table>
        <div class="mt-3 flex justify-end">
          <Pagination
            v-model:current="phonePage.pageNum"
            v-model:page-size="phonePage.pageSize"
            :total="phonePage.total"
            show-size-changer
            @change="loadPhone"
          />
        </div>
      </TabPane>

      <!-- Tab3 银行卡 BIN -->
      <TabPane key="bin" tab="银行卡 BIN">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Input
            v-model:value="binKeyword"
            class="max-w-[200px]"
            placeholder="BIN 或发卡行"
            allow-clear
            @pressEnter="loadBin"
          />
          <Select
            v-model:value="binBankName"
            class="w-[220px]"
            :options="bankOptions"
            placeholder="发卡行"
            allow-clear
            show-search
            :filter-option="(input: string, option: any) => option.label.includes(input)"
          />
          <Button type="primary" @click="loadBin">查询</Button>
          <Button v-if="canEdit" type="primary" ghost @click="openBinCreate">新增 BIN</Button>
        </div>
        <Table
          :columns="binColumns"
          :data-source="binRows"
          :loading="binLoading"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'cardType'">
              {{ cardTypeText((record as BaseDataApi.BankBin).cardType) }}
            </template>
            <template v-else-if="column.dataIndex === 'note'">
              {{ (record as BaseDataApi.BankBin).note || '-' }}
            </template>
            <template v-else-if="column.dataIndex === 'action' && canEdit">
              <Button size="small" type="link" @click="openBinEdit(record as BaseDataApi.BankBin)">编辑</Button>
              <Popconfirm title="确定删除该 BIN？" @confirm="removeBin(record as BaseDataApi.BankBin)">
                <Button danger size="small" type="link">删除</Button>
              </Popconfirm>
            </template>
          </template>
        </Table>
        <div class="mt-3 flex justify-end">
          <Pagination
            v-model:current="binPage.pageNum"
            v-model:page-size="binPage.pageSize"
            :total="binPage.total"
            show-size-changer
            @change="loadBin"
          />
        </div>
      </TabPane>

      <!-- Tab4 同步日志 -->
      <TabPane key="log" tab="同步日志">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Select
            v-model:value="logType"
            class="w-[160px]"
            :options="[
              { label: '行政区划', value: 'region' },
              { label: '手机号段', value: 'phone' },
              { label: '银行卡 BIN', value: 'bin' },
            ]"
            placeholder="数据类型"
            allow-clear
          />
          <Button type="primary" @click="loadLog">查询</Button>
        </div>
        <Table
          :columns="logColumns"
          :data-source="logRows"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'dataType'">
              {{ typeNames[(record as BaseDataApi.SyncLog).dataType] ?? record.dataType }}
            </template>
            <template v-else-if="column.dataIndex === 'triggerType'">
              {{ (record as BaseDataApi.SyncLog).triggerType === 1 ? '手动' : '定时' }}
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <Tag :color="logStatusTag((record as BaseDataApi.SyncLog).status).color">
                {{ logStatusTag((record as BaseDataApi.SyncLog).status).text }}
              </Tag>
            </template>
            <template v-else-if="['rowsTotal', 'rowsInserted', 'rowsUpdated', 'rowsDisabled', 'startedAt', 'finishedAt'].includes(column.dataIndex as string)">
              {{ formatCell((record as Record<string, unknown>)[column.dataIndex as string]) }}
            </template>
            <template v-else-if="column.dataIndex === 'message'">
              {{ (record as BaseDataApi.SyncLog).message || '-' }}
            </template>
          </template>
        </Table>
        <div class="mt-3 flex justify-end">
          <Pagination
            v-model:current="logPage.pageNum"
            v-model:page-size="logPage.pageSize"
            :total="logPage.total"
            show-size-changer
            @change="loadLog"
          />
        </div>
      </TabPane>
    </Tabs>

    <!-- 号段编辑 Modal -->
    <Modal
      v-model:open="phoneModal"
      :title="phoneEditing ? '编辑号段' : '新增号段'"
      @ok="submitPhone"
    >
      <div class="flex flex-col gap-3 pt-2">
        <div>
          <div class="mb-1 text-sm">号段前缀（3-4 位数字）</div>
          <Input v-model:value="phoneForm.prefix" placeholder="如 138 / 1700" />
        </div>
        <div>
          <div class="mb-1 text-sm">运营商</div>
          <Input v-model:value="phoneForm.operator" placeholder="移动 / 联通 / 电信 / 广电 / 虚拟运营商" />
        </div>
        <div>
          <div class="mb-1 text-sm">卡类型</div>
          <Select
            v-model:value="phoneForm.segType"
            :options="[
              { label: '基础运营商', value: 1 },
              { label: '虚拟运营商', value: 2 },
              { label: '物联卡', value: 3 },
            ]"
            class="w-full"
          />
        </div>
        <div>
          <div class="mb-1 text-sm">备注</div>
          <Input v-model:value="phoneForm.note" placeholder="可选" />
        </div>
      </div>
    </Modal>

    <!-- BIN 编辑 Modal -->
    <Modal
      v-model:open="binModal"
      :title="binEditing ? '编辑 BIN' : '新增 BIN'"
      @ok="submitBin"
    >
      <div class="flex flex-col gap-3 pt-2">
        <div>
          <div class="mb-1 text-sm">BIN（6-10 位数字）</div>
          <Input v-model:value="binForm.bin" placeholder="如 622202" />
        </div>
        <div>
          <div class="mb-1 text-sm">发卡行全称</div>
          <Input v-model:value="binForm.bankName" placeholder="如 中国工商银行" />
        </div>
        <div>
          <div class="mb-1 text-sm">简称</div>
          <Input v-model:value="binForm.bankShort" placeholder="如 工行（可选）" />
        </div>
        <div class="flex gap-3">
          <div class="flex-1">
            <div class="mb-1 text-sm">卡种</div>
            <Select
              v-model:value="binForm.cardType"
              :options="[
                { label: '借记卡', value: 1 },
                { label: '贷记卡（信用卡）', value: 2 },
                { label: '准贷记卡', value: 3 },
              ]"
              class="w-full"
            />
          </div>
          <div class="flex-1">
            <div class="mb-1 text-sm">标准卡长</div>
            <Select
              v-model:value="binForm.cardLen"
              :options="[
                { label: '16 位', value: 16 },
                { label: '19 位', value: 19 },
              ]"
              class="w-full"
            />
          </div>
        </div>
        <div>
          <div class="mb-1 text-sm">备注</div>
          <Input v-model:value="binForm.note" placeholder="可选" />
        </div>
      </div>
    </Modal>
  </Page>
</template>
