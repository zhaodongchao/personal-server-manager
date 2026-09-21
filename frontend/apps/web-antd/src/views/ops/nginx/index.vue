<script lang="ts" setup>
/**
 * Nginx 管理页（运维工具 / 静态配置生成型）。
 *
 * <p>参照 NginxProxyManager 的「Web UI 录入 → 存库 → 模板渲染 → 写配置 →
 * `nginx -t` → reload」闭环。本页作为总控：实例切换 + 运行态状态区 + 站点表格，
 * 其余能力（上游组 / 四层转发 / 证书 / 变更回滚 / 日志 / 既有站点）经抽屉打开。
 *
 * <p>实例可配置：未指定时后端自动探测主机默认 nginx。所有写操作均返回
 * {@link NginxApi.NginxActionResult}（命令 + 差异 + 回滚快照）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { NginxApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Modal,
  Select,
  Space,
  Tag,
  message,
} from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createNginxSiteApi,
  deleteNginxSiteApi,
  getNginxInstancesApi,
  getNginxSiteConfApi,
  getNginxSitePageApi,
  getOpsNginxStatusApi,
  reloadNginxApi,
  testNginxApi,
  toggleNginxSiteApi,
  updateNginxSiteApi,
} from '#/api';

import {
  primaryDomain,
  siteTypeColor,
  siteTypeLabel,
  sslModeColor,
  sslModeLabel,
} from './utils';

import CertDrawer from './components/CertDrawer.vue';
import ChangeHistoryDrawer from './components/ChangeHistoryDrawer.vue';
import DangerConfirmModal from './components/DangerConfirmModal.vue';
import ExistingSiteDrawer from './components/ExistingSiteDrawer.vue';
import InstanceDrawer from './components/InstanceDrawer.vue';
import LogViewerDrawer from './components/LogViewerDrawer.vue';
import SiteFormModal from './components/SiteFormModal.vue';
import StreamDrawer from './components/StreamDrawer.vue';
import UpstreamDrawer from './components/UpstreamDrawer.vue';

defineOptions({ name: 'OpsNginx' });

const { hasAccessByCodes } = useAccess();

const canSiteWrite = computed(() => hasAccessByCodes(['ops:nginx:site:write']));
const canCert = computed(() => hasAccessByCodes(['ops:nginx:cert']));
const canReload = computed(() => hasAccessByCodes(['ops:nginx:reload']));
const canRollback = computed(() => hasAccessByCodes(['ops:nginx:rollback']));
const canInstance = computed(() => hasAccessByCodes(['ops:nginx:instance']));
const canStream = computed(() => hasAccessByCodes(['ops:nginx:stream']));

// ==================== 实例 ====================

const instances = ref<NginxApi.NginxInstance[]>([]);
const instanceId = ref<string>();
const status = ref<NginxApi.NginxStatus>();
const statusLoading = ref(false);

async function loadInstances() {
  instances.value = (await getNginxInstancesApi()) ?? [];
  if (!instanceId.value && instances.value.length > 0) {
    const def = instances.value.find((i) => i.defaultFlag === 1) ?? instances.value[0];
    instanceId.value = def?.id;
  }
}

async function loadStatus() {
  statusLoading.value = true;
  try {
    status.value = await getOpsNginxStatusApi(instanceId.value);
  } finally {
    statusLoading.value = false;
  }
}

// ==================== 站点表格 ====================

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '站点名或域名' },
      fieldName: 'keyword',
      label: '关键字',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { type: 'seq', width: 50, title: '#' },
    {
      field: 'name',
      minWidth: 150,
      slots: { default: 'name' },
      title: '站点名',
    },
    {
      field: 'domains',
      minWidth: 220,
      slots: { default: 'domains' },
      title: '域名',
    },
    {
      field: 'siteType',
      slots: { default: 'siteType' },
      title: '类型',
      width: 90,
    },
    {
      field: 'sslMode',
      slots: { default: 'sslMode' },
      title: 'HTTPS',
      width: 120,
    },
    {
      field: 'status',
      slots: { default: 'status' },
      title: '状态',
      width: 90,
    },
    { field: 'remark', minWidth: 120, title: '备注' },
    {
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: '操作',
      width: 230,
    },
  ],
  pagerConfig: { pageSize: 20, pageSizes: [20, 50, 100] },
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const res = await getNginxSitePageApi({
          instanceId: instanceId.value,
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          keyword: (formValues as Record<string, unknown>).keyword as
            | string
            | undefined,
        });
        return { items: res?.records ?? [], total: res?.total ?? 0 };
      },
    },
  },
  rowConfig: { keyField: 'id' },
};

const [Grid, gridApi] = useVbenVxeGrid({ formOptions, gridOptions });

async function reloadAll() {
  await loadStatus();
  gridApi.query();
}

// ==================== 站点表单 ====================

const siteFormOpen = ref(false);
const editingSite = ref<NginxApi.NginxSite | null>(null);

function openCreateSite() {
  editingSite.value = null;
  siteFormOpen.value = true;
}

function openEditSite(row: NginxApi.NginxSite) {
  editingSite.value = row;
  siteFormOpen.value = true;
}

async function onSubmitSite(body: NginxApi.NginxSiteBody) {
  const res = body.id
    ? await updateNginxSiteApi(body)
    : await createNginxSiteApi(body);
  message.success(res?.message ?? '站点已保存并生效');
  siteFormOpen.value = false;
  await reloadAll();
}

// ==================== 站点动作 ====================

async function toggleSite(row: NginxApi.NginxSite) {
  const next = row.status === 1 ? 0 : 1;
  await toggleNginxSiteApi(row.id as string, next);
  message.success(next === 1 ? '站点已启用' : '站点已停用');
  await reloadAll();
}

const deleteOpen = ref(false);
const deleteTarget = ref<NginxApi.NginxSite | null>(null);

function askDeleteSite(row: NginxApi.NginxSite) {
  deleteTarget.value = row;
  deleteOpen.value = true;
}

async function doDeleteSite(keyword: string) {
  const row = deleteTarget.value;
  if (!row) return;
  const res = await deleteNginxSiteApi(row.id as string, keyword);
  message.success(res?.message ?? '站点已删除');
  await reloadAll();
}

// ==================== 站点配置查看 ====================

const confOpen = ref(false);
const confLoading = ref(false);
const confText = ref('');

async function openConf(row: NginxApi.NginxSite) {
  confLoading.value = true;
  confOpen.value = true;
  confText.value = '';
  try {
    confText.value = await getNginxSiteConfApi(row.id as string);
  } finally {
    confLoading.value = false;
  }
}

// ==================== 运维动作 ====================

const busy = ref('');

async function doTest() {
  busy.value = 'test';
  try {
    const res = await testNginxApi();
    message.success(res?.message ?? 'nginx -t 通过');
  } finally {
    busy.value = '';
    await loadStatus();
  }
}

async function doReload() {
  busy.value = 'reload';
  try {
    const res = await reloadNginxApi();
    message.success(res?.message ?? 'nginx 已重载');
  } finally {
    busy.value = '';
    await loadStatus();
  }
}

// ==================== 抽屉 ====================

const instanceDrawerOpen = ref(false);
const upstreamDrawerOpen = ref(false);
const streamDrawerOpen = ref(false);
const certDrawerOpen = ref(false);
const changeDrawerOpen = ref(false);
const logDrawerOpen = ref(false);
const existingDrawerOpen = ref(false);

// ==================== 生命周期 ====================

onMounted(async () => {
  await loadInstances();
  await reloadAll();
});
</script>

<template>
  <Page>
    <!-- 实例切换 + 状态区 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <span class="text-sm text-gray-500">Nginx 实例</span>
      <Select
        v-model:value="instanceId"
        :options="instances.map((i) => ({
          label: `${i.name}${i.defaultFlag === 1 ? '（默认）' : ''}`,
          value: i.id as string,
        }))"
        class="w-72"
        placeholder="选择实例"
        @change="reloadAll"
      />
      <Button v-if="canInstance" size="small" @click="instanceDrawerOpen = true">
        管理实例
      </Button>
      <Button size="small" @click="reloadAll">刷新状态</Button>
      <Space class="ml-auto" :size="4">
        <Button v-if="canReload" :loading="busy === 'test'" size="small" @click="doTest">
          测试配置
        </Button>
        <Button
          v-if="canReload"
          :loading="busy === 'reload'"
          size="small"
          type="primary"
          @click="doReload"
        >
          重载 nginx
        </Button>
      </Space>
    </div>

    <!-- 状态卡 -->
    <div class="mb-3 grid grid-cols-2 gap-2 md:grid-cols-4 xl:grid-cols-8">
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">nginx 版本</div>
        <div class="text-sm font-semibold">{{ status?.nginxVersion ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">配置校验</div>
        <div class="text-sm font-semibold" :class="status?.configValid ? 'text-green-600' : 'text-red-600'">
          {{ status?.configValid ? '通过' : status?.nginxAvailable ? '未通过' : '未知' }}
        </div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">certbot</div>
        <div class="text-sm font-semibold">{{ status?.certbotVersion ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">站点</div>
        <div class="text-sm font-semibold">{{ status?.siteCount ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">上游组</div>
        <div class="text-sm font-semibold">{{ status?.upstreamCount ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">四层转发</div>
        <div class="text-sm font-semibold">{{ status?.streamCount ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">证书</div>
        <div class="text-sm font-semibold">{{ status?.certCount ?? '-' }}</div>
      </div>
      <div class="rounded border border-gray-200 p-2 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">通道</div>
        <div class="text-sm font-semibold" :class="status?.channelOk ? 'text-green-600' : 'text-red-600'">
          {{ status?.channelOk ? '可用' : '不可用' }}
        </div>
      </div>
    </div>

    <!-- 告警 -->
    <Alert
      v-if="status && !status.channelOk"
      class="mb-3"
      message="宿主执行通道不可用，nginx 探测 / 校验 / 重载 / 证书申请均无法执行，请检查宿主代理。"
      show-icon
      type="error"
    />
    <Alert
      v-else-if="status && status.nginxAvailable && !status.configValid"
      class="mb-3"
      message="nginx -t 未通过，请检查最近一次变更或主配置文件。"
      show-icon
      type="error"
    />
    <Alert
      v-if="status?.expiringCerts && status.expiringCerts.length > 0"
      class="mb-3"
      message="有证书即将到期（≤30 天）"
      show-icon
      type="warning"
    >
      <template #description>
        <div class="flex flex-wrap gap-1">
          <Tag v-for="d in status.expiringCerts" :key="d" color="orange">{{ d }}</Tag>
        </div>
      </template>
    </Alert>

    <Grid>
      <template #toolbar-tools>
        <Space :size="4">
          <Button v-if="canSiteWrite" size="small" type="primary" @click="openCreateSite">
            新建站点
          </Button>
          <Button v-if="canSiteWrite" size="small" @click="upstreamDrawerOpen = true">
            上游组
          </Button>
          <Button v-if="canStream" size="small" @click="streamDrawerOpen = true">
            四层转发
          </Button>
          <Button v-if="canCert" size="small" @click="certDrawerOpen = true">
            证书
          </Button>
          <Button size="small" @click="changeDrawerOpen = true">变更历史</Button>
          <Button size="small" @click="logDrawerOpen = true">日志</Button>
          <Button size="small" @click="existingDrawerOpen = true">现有站点</Button>
        </Space>
      </template>

      <template #name="{ row }">
        <a class="font-medium" @click="openEditSite(row as NginxApi.NginxSite)">
          {{ row.name }}
        </a>
      </template>

      <template #domains="{ row }">
        <div class="font-mono text-xs">
          {{ primaryDomain((row as NginxApi.NginxSite).domains) }}
        </div>
      </template>

      <template #siteType="{ row }">
        <Tag :color="siteTypeColor((row as NginxApi.NginxSite).siteType)">
          {{ siteTypeLabel((row as NginxApi.NginxSite).siteType) }}
        </Tag>
      </template>

      <template #sslMode="{ row }">
        <Tag :color="sslModeColor((row as NginxApi.NginxSite).sslMode)">
          {{ sslModeLabel((row as NginxApi.NginxSite).sslMode) }}
        </Tag>
      </template>

      <template #status="{ row }">
        <Tag :color="(row as NginxApi.NginxSite).status === 1 ? 'green' : 'default'">
          {{ (row as NginxApi.NginxSite).status === 1 ? '启用' : '停用' }}
        </Tag>
      </template>

      <template #action="{ row }">
        <div class="flex items-center justify-center gap-1">
          <template v-if="canSiteWrite">
            <Button size="small" type="link" @click="openEditSite(row as NginxApi.NginxSite)">
              编辑
            </Button>
            <Button size="small" type="link" @click="toggleSite(row as NginxApi.NginxSite)">
              {{ (row as NginxApi.NginxSite).status === 1 ? '停用' : '启用' }}
            </Button>
            <Button
              danger
              size="small"
              type="link"
              @click="askDeleteSite(row as NginxApi.NginxSite)"
            >
              删除
            </Button>
          </template>
          <Button size="small" type="link" @click="openConf(row as NginxApi.NginxSite)">
            配置
          </Button>
        </div>
      </template>
    </Grid>

    <!-- 站点表单 -->
    <SiteFormModal
      v-model:open="siteFormOpen"
      :instance-id="instanceId"
      :site="editingSite"
      @submit="onSubmitSite"
    />

    <!-- 实例管理 -->
    <InstanceDrawer
      v-model:open="instanceDrawerOpen"
      :current-id="instanceId"
      @changed="(id?: string) => { if (id) { instanceId = id; } loadInstances(); reloadAll(); }"
    />

    <!-- 上游组 / 四层 / 证书 / 变更 / 日志 / 既有站点 -->
    <UpstreamDrawer
      v-model:open="upstreamDrawerOpen"
      :can-write="canSiteWrite"
      :instance-id="instanceId"
      @changed="reloadAll"
    />
    <StreamDrawer
      v-model:open="streamDrawerOpen"
      :can-write="canStream"
      :instance-id="instanceId"
      @changed="reloadAll"
    />
    <CertDrawer
      v-model:open="certDrawerOpen"
      :can-write="canCert"
      :instance-id="instanceId"
      @changed="reloadAll"
    />
    <ChangeHistoryDrawer
      v-model:open="changeDrawerOpen"
      :can-rollback="canRollback"
      :instance-id="instanceId"
      @rolled-back="reloadAll"
    />
    <LogViewerDrawer v-model:open="logDrawerOpen" :instance-id="instanceId" />
    <ExistingSiteDrawer v-model:open="existingDrawerOpen" :instance-id="instanceId" />

    <!-- 站点删除确认 -->
    <DangerConfirmModal
      v-model:open="deleteOpen"
      :keyword="deleteTarget?.name ?? ''"
      description="删除站点将立即移除其反代/静态服务，需二次确认。"
      title="删除站点"
      @confirm="doDeleteSite"
    />

    <!-- 站点配置查看 -->
    <Modal v-model:open="confOpen" :footer="null" :width="720" title="站点配置">
      <pre
        class="max-h-[60vh] overflow-auto rounded bg-gray-50 p-3 font-mono text-xs leading-5 dark:bg-gray-900"
      >{{ confText || '加载中…' }}</pre>
    </Modal>
  </Page>
</template>
