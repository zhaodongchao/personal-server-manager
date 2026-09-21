<script lang="ts" setup>
/**
 * 防火墙管理页面
 *
 * <p>重构要点（对应重构方案第六节）：
 * <ol>
 *   <li><b>解析修正。</b>旧版把 ufw 的 {@code ALLOW IN} 里的 {@code IN} 混进了来源列，
 *       于是出现「来源 = IN 172.238.101.222」；且范围端口 {@code 40000:40100/tcp}、
 *       Anywhere 类规则（fail2ban 的 REJECT）在页面上「不支持从面板删除」。
 *       现在动作与方向拆成两列、目标形态可识别，55 条规则全部可解析。</li>
 *   <li><b>按编号 + 指纹删除。</b>ufw 的规则编号会随增删重排，按端口删除重复规则必删错；
 *       删除时同时提交 {@code fingerprint}，编号漂移会被后端直接拒绝（5014）。</li>
 *   <li><b>生存线保护。</b>任何可能切断 SSH / 面板端口的操作都要键入 {@code SSH 22} 这类关键字；
 *       后端仍会再拦一次，前端只是把风险提前暴露。</li>
 *   <li><b>看门狗。</b>全局开关类操作生效即挂倒计时，超时未确认由宿主机自动回滚，
 *       避免「改完就失联、连撤销的机会都没有」。</li>
 *   <li><b>变更可回滚。</b>每次写操作保存前后快照与结构化 diff，历史抽屉里可比对并一键回滚。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { OpsApi } from '#/api';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
  message,
} from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  addFirewallRuleApi,
  confirmFirewallWatchdogApi,
  deleteFirewallRuleApi,
  disableFirewallApi,
  enableFirewallApi,
  getFirewallRawApi,
  getFirewallStatusApi,
  getFirewallWatchdogApi,
  getHostCapabilityApi,
  reloadFirewallApi,
  rollbackFirewallChangeApi,
  setFirewallDefaultPolicyApi,
} from '#/api';

import ChangeHistoryDrawer from './components/ChangeHistoryDrawer.vue';
import RuleFormModal from './components/RuleFormModal.vue';
import WatchdogBar from './components/WatchdogBar.vue';
import HostChannelBanner from '../components/HostChannelBanner.vue';

import {
  ACTION_OPTIONS,
  PROVENANCE_OPTIONS,
  actionColor,
  actionLabel,
  directionLabel,
  evaluateDeleteRisk,
  provenanceMeta,
  sourceKindLabel,
  toKindLabel,
} from './utils';

defineOptions({ name: 'OpsFirewall' });

const { hasAccessByCodes } = useAccess();

const canWrite = computed(() => hasAccessByCodes(['ops:firewall:write']));
const canDanger = computed(() => hasAccessByCodes(['ops:firewall:danger']));
const canRollback = computed(() => hasAccessByCodes(['ops:firewall:rollback']));

// ==================== 宿主通道 ====================

const capability = ref<OpsApi.HostCapability>();
const channelOk = computed(
  () => !!capability.value?.ok && (capability.value?.protocol ?? 0) >= 1,
);
/** 通道不可用时整页只读——在容器里跑 ufw 是空转，不如明确告诉使用者 */
const writable = computed(() => channelOk.value);

async function loadCapability() {
  try {
    capability.value = await getHostCapabilityApi();
  } catch {
    capability.value = undefined;
  }
}

// ==================== 状态 ====================

const loading = ref(false);
const status = ref<OpsApi.FirewallStatus>({
  active: false,
  available: false,
  backend: 'none',
  ipv6: false,
  ruleCount: 0,
  rules: [],
});
const watchdog = ref<OpsApi.FirewallWatchdog | null>(null);
const acting = ref('');

const guard = computed(() => status.value.guard);
const guardWarnings = computed(() => guard.value?.warnings ?? []);

async function loadStatus() {
  loading.value = true;
  try {
    status.value = await getFirewallStatusApi();
  } finally {
    loading.value = false;
  }
}

async function loadWatchdog() {
  try {
    const w = await getFirewallWatchdogApi();
    watchdog.value = w && w.id && (w.secondsLeft ?? 0) > 0 ? w : null;
  } catch {
    watchdog.value = null;
  }
}

async function reloadAll() {
  await loadStatus();
  await loadWatchdog();
  gridApi.query();
}

// ==================== 列表（VXE-Table，客户端筛选 + 自切片分页） ====================

const DIRECTION_OPTIONS = [
  { label: '入站', value: 'in' },
  { label: '出站', value: 'out' },
  { label: '转发', value: 'fwd' },
];

const formOptions: VbenFormProps = {
  collapsed: false,
  schema: [
    {
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '端口 / 来源 / 备注',
      },
      fieldName: 'keyword',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ACTION_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'action',
      label: '动作',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DIRECTION_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'direction',
      label: '方向',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PROVENANCE_OPTIONS,
        placeholder: '全部',
      },
      fieldName: 'provenance',
      label: '来源标记',
    },
  ],
};

const gridOptions: VxeTableGridOptions = {
  columns: [
    { field: 'no', title: '编号', width: 70 },
    { field: 'to', minWidth: 190, slots: { default: 'to' }, title: '目标' },
    {
      field: 'action',
      slots: { default: 'action' },
      title: '动作',
      width: 110,
    },
    {
      field: 'direction',
      slots: { default: 'direction' },
      title: '方向',
      width: 80,
    },
    { field: 'from', minWidth: 180, slots: { default: 'from' }, title: '来源' },
    {
      field: 'comment',
      minWidth: 150,
      slots: { default: 'comment' },
      title: '备注',
    },
    {
      field: 'provenance',
      slots: { default: 'provenance' },
      title: '来源标记',
      width: 100,
    },
    {
      field: 'ops',
      fixed: 'right',
      slots: { default: 'ops' },
      title: '操作',
      width: 140,
    },
  ],
  pagerConfig: { pageSize: 20, pageSizes: [20, 50, 100] },
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues) => {
        const fv = (formValues ?? {}) as {
          keyword?: string;
          action?: string;
          direction?: string;
          provenance?: string;
        };
        let list = status.value.rules ?? [];
        const kw = fv.keyword?.trim().toLowerCase();
        if (kw) {
          list = list.filter((r) =>
            [r.to, r.from, r.comment]
              .filter(Boolean)
              .some((x) => String(x).toLowerCase().includes(kw)),
          );
        }
        if (fv.action) list = list.filter((r) => r.action === fv.action);
        if (fv.direction) {
          list = list.filter((r) => r.direction === fv.direction);
        }
        if (fv.provenance) {
          list = list.filter((r) => r.provenance === fv.provenance);
        }
        const start = ((page.currentPage ?? 1) - 1) * (page.pageSize ?? 20);
        return {
          items: list.slice(start, start + (page.pageSize ?? 20)),
          total: list.length,
        };
      },
    },
  },
  rowConfig: { keyField: 'no' },
};

const [Grid, gridApi] = useVbenVxeGrid({ formOptions, gridOptions });

// ==================== 高危确认弹窗（统一入口） ====================

interface DangerState {
  open: boolean;
  title: string;
  reason: string;
  /** 需要键入的确认关键字（为空则只需点确认） */
  keyword: string;
  /** 是否为外部托管规则（需要勾选「强制」） */
  needForce: boolean;
  run: (() => Promise<void>) | null;
}

const danger = ref<DangerState>(blankDanger());
const dangerInput = ref('');
const dangerForce = ref(false);
const dangerRunning = ref(false);

function blankDanger(): DangerState {
  return {
    open: false,
    title: '',
    reason: '',
    keyword: '',
    needForce: false,
    run: null,
  };
}

function openDanger(cfg: Omit<DangerState, 'open'>) {
  danger.value = { ...blankDanger(), ...cfg, open: true };
  dangerInput.value = '';
  dangerForce.value = false;
}

const dangerReady = computed(() => {
  const d = danger.value;
  const kwOk = !d.keyword || dangerInput.value.trim().toUpperCase() === d.keyword.toUpperCase();
  const forceOk = !d.needForce || dangerForce.value;
  return kwOk && forceOk;
});

async function runDanger() {
  if (!dangerReady.value || !danger.value.run) return;
  dangerRunning.value = true;
  try {
    await danger.value.run();
    danger.value.open = false;
  } finally {
    dangerRunning.value = false;
  }
}

// ==================== 规则增删 ====================

const ruleOpen = ref(false);
const ruleSource = ref<OpsApi.FirewallRule | null>(null);

function openCreate() {
  ruleSource.value = null;
  ruleOpen.value = true;
}

function openCopy(row: OpsApi.FirewallRule) {
  ruleSource.value = row;
  ruleOpen.value = true;
}

async function onSubmitRule(body: OpsApi.FirewallRuleBody) {
  acting.value = 'add';
  try {
    await addFirewallRuleApi(body);
    message.success('规则已添加');
    ruleOpen.value = false;
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '添加失败');
  } finally {
    acting.value = '';
  }
}

function confirmDelete(row: OpsApi.FirewallRule) {
  const risk = evaluateDeleteRisk(row, guard.value);
  const forceNeeded = !row.deletable;
  const keyword = risk?.level === 'danger' ? (risk.keyword ?? '') : '';
  // ufw 开启 IPv6 时一次 add 会写两条（本体 + v6 副本），后端按同指纹一并删除，
  // 这里必须明说：用户点的是一行，实际动的是两条
  const twin = (status.value.rules ?? []).find(
    (r) =>
      r.no !== row.no &&
      r.fingerprint === row.fingerprint &&
      r.ipv6 !== row.ipv6,
  );
  const reasons = [
    risk?.text,
    forceNeeded ? '该规则由外部程序（如 Fail2Ban）维护，删除后可能被自动重建。' : '',
    twin ? `存在 IPv6 副本（#${twin.no}），将一并删除。` : '',
  ]
    .filter(Boolean)
    .join(' ');
  openDanger({
    title: `删除规则 #${row.no}`,
    reason:
      reasons ||
      `将删除「${row.to} ${actionLabel(row.action)} 来自 ${row.from || '任意来源'}」`,
    keyword,
    needForce: forceNeeded,
    run: async () => {
      await deleteFirewallRuleApi({
        no: row.no,
        fingerprint: row.fingerprint,
        confirm: keyword || undefined,
        force: forceNeeded ? true : undefined,
      });
      message.success('规则已删除');
      await reloadAll();
    },
  });
}

// ==================== 全局开关 ====================

async function onToggleFirewall(enabled: boolean) {
  if (enabled) {
    const denyIncoming =
      status.value.defaultPolicy?.incoming?.toLowerCase() === 'deny';
    const sshBlocked = !!guard.value && !guard.value.sshAllowed;
    if (denyIncoming && sshBlocked) {
      const port = guard.value?.sshPorts?.[0] ?? 22;
      openDanger({
        title: '启用防火墙',
        reason: `默认入站策略为 deny，但 SSH 端口 ${port} 没有放行规则，启用后可能无法远程登录。确认请键入 SSH ${port}。`,
        keyword: `SSH ${port}`,
        needForce: false,
        run: () => doSetEnabled(true, `SSH ${port}`),
      });
      return;
    }
    await doSetEnabled(true);
    return;
  }
  openDanger({
    title: '停用防火墙',
    reason: '停用后主机将完全暴露在公网，需二次确认。确认请键入 DISABLE。',
    keyword: 'DISABLE',
    needForce: false,
    run: () => doSetEnabled(false, 'DISABLE'),
  });
}

async function doSetEnabled(enabled: boolean, confirm?: string) {
  acting.value = enabled ? 'enable' : 'disable';
  try {
    const res = enabled
      ? await enableFirewallApi(confirm)
      : await disableFirewallApi(confirm);
    message.success(enabled ? '防火墙已启用' : '防火墙已停用');
    watchdog.value = res?.watchdog ?? null;
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '操作失败');
  } finally {
    acting.value = '';
  }
}

// ==================== 默认策略 ====================

const POLICY_OPTIONS = [
  { label: '放行（allow）', value: 'allow' },
  { label: '拒绝（deny）', value: 'deny' },
  { label: '拒绝并回包（reject）', value: 'reject' },
];

const policyOpen = ref(false);
const policyForm = ref<{ incoming?: string; outgoing?: string; routed?: string }>(
  {},
);
const policyConfirm = ref('');

function openPolicy() {
  policyForm.value = {
    incoming: status.value.defaultPolicy?.incoming,
    outgoing: status.value.defaultPolicy?.outgoing,
    routed: status.value.defaultPolicy?.routed,
  };
  policyConfirm.value = '';
  policyOpen.value = true;
}

/** 收紧入站策略且 SSH 未放行 → 要求键入关键字（口径同后端 setDefault） */
const policyKeyword = computed(() => {
  const v = policyForm.value.incoming?.toLowerCase();
  const tightening = v === 'deny' || v === 'reject';
  if (tightening && guard.value && !guard.value.sshAllowed) {
    return `SSH ${guard.value.sshPorts?.[0] ?? 22}`;
  }
  return '';
});

const policyReady = computed(
  () =>
    !!(
      policyForm.value.incoming ||
      policyForm.value.outgoing ||
      policyForm.value.routed
    ) &&
    (!policyKeyword.value ||
      policyConfirm.value.trim().toUpperCase() ===
        policyKeyword.value.toUpperCase()),
);

async function submitPolicy() {
  if (!policyReady.value) return;
  acting.value = 'policy';
  try {
    await setFirewallDefaultPolicyApi({
      ...policyForm.value,
      confirm: policyKeyword.value ? policyKeyword.value : undefined,
    });
    message.success('默认策略已更新');
    policyOpen.value = false;
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '设置失败');
  } finally {
    acting.value = '';
  }
}

// ==================== 重载 / 原始规则 ====================

async function onReload() {
  acting.value = 'reload';
  try {
    await reloadFirewallApi();
    message.success('防火墙已重载');
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '重载失败');
  } finally {
    acting.value = '';
  }
}

const rawOpen = ref(false);
const rawText = ref('');

async function openRaw() {
  rawOpen.value = true;
  rawText.value = '';
  try {
    rawText.value = await getFirewallRawApi();
  } catch (e: any) {
    rawText.value = `读取失败：${e?.message ?? '未知错误'}`;
  }
}

// ==================== 变更历史 ====================

const historyOpen = ref(false);

async function onRollback(id: string, confirm: string) {
  acting.value = `rollback-${id}`;
  try {
    await rollbackFirewallChangeApi(id, confirm);
    message.success('已回滚');
    historyOpen.value = false;
    await reloadAll();
  } catch (e: any) {
    message.error(e?.message ?? '回滚失败');
  } finally {
    acting.value = '';
  }
}

// ==================== 看门狗 ====================

async function onKeepChange() {
  acting.value = 'watchdog';
  try {
    await confirmFirewallWatchdogApi();
    message.success('已保留变更，看门狗已撤销');
    watchdog.value = null;
  } catch (e: any) {
    message.error(e?.message ?? '操作失败');
  } finally {
    acting.value = '';
  }
}

let watchdogTimer: ReturnType<typeof setInterval> | undefined;

onMounted(() => {
  void loadCapability();
  void loadStatus().then(() => {
    gridApi.query();
    void loadWatchdog();
  });
  // 看门狗倒计时由前端推进，这里只做兜底同步（例如别的管理员在别处确认了）
  watchdogTimer = setInterval(() => {
    if (watchdog.value) void loadWatchdog();
  }, 30_000);
});

onBeforeUnmount(() => {
  if (watchdogTimer) clearInterval(watchdogTimer);
});
</script>

<template>
  <Page auto-content-height>
    <HostChannelBanner :capability="capability" @refreshed="loadCapability" />

    <!-- 状态头区：一眼看清「用的什么后端、开没开、默认策略是什么、有没有把自己锁在外面的风险」 -->
    <div class="mb-3 rounded border border-gray-200 bg-white px-4 py-3">
      <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Tag :color="status.available ? (status.active ? 'green' : 'orange') : 'default'">
          {{ status.backend === 'none' ? '未检测到防火墙' : status.backend }}
        </Tag>
        <Tag v-if="status.available" :color="status.active ? 'green' : 'red'">
          {{ status.active ? '已启用' : '未启用' }}
        </Tag>
        <Tag v-if="status.version">{{ status.version }}</Tag>
        <Tag :color="status.ipv6 ? 'blue' : 'default'">
          IPv6 {{ status.ipv6 ? '已开启' : '未开启' }}
        </Tag>
        <Tag v-if="status.logging">日志：{{ status.logging }}</Tag>
        <Tag v-if="status.defaultPolicy?.incoming">
          默认入站：{{ status.defaultPolicy.incoming }}
        </Tag>
        <Tag v-if="status.defaultPolicy?.outgoing">
          默认出站：{{ status.defaultPolicy.outgoing }}
        </Tag>
        <span class="text-sm text-gray-500">
          共 {{ status.ruleCount ?? status.rules?.length ?? 0 }} 条规则
        </span>
        <span v-if="guard?.clientIp" class="text-xs text-gray-400">
          你从 {{ guard.clientIp }} 访问
        </span>

        <Space class="ml-auto">
          <Button
            v-if="canDanger"
            :disabled="!writable || !status.available"
            :loading="acting === 'enable'"
            danger
            size="small"
            type="primary"
            @click="onToggleFirewall(true)"
          >
            启用
          </Button>
          <Button
            v-if="canDanger"
            :disabled="!writable || !status.available"
            :loading="acting === 'disable'"
            danger
            size="small"
            @click="onToggleFirewall(false)"
          >
            停用
          </Button>
          <Button
            v-if="canDanger"
            :disabled="!writable || !status.available"
            size="small"
            @click="openPolicy"
          >
            默认策略
          </Button>
          <Popconfirm title="重载防火墙？" @confirm="onReload">
            <Button
              v-if="canWrite"
              :disabled="!writable || !status.available"
              :loading="acting === 'reload'"
              size="small"
            >
              重载
            </Button>
          </Popconfirm>
          <Button size="small" @click="openRaw">原始规则</Button>
          <Button size="small" @click="historyOpen = true">变更历史</Button>
        </Space>
      </div>

      <Alert
        v-if="!status.available"
        class="mt-3"
        message="未检测到可用的防火墙（ufw / firewalld）。当前面板经宿主代理在宿主机上执行，请确认宿主机已安装其中之一。"
        show-icon
        type="warning"
      />
      <Alert
        v-else-if="!status.active"
        class="mt-3"
        :message="`检测到 ${status.backend} 但当前未启用，规则不会生效`"
        show-icon
        type="warning"
      />
      <div v-if="guardWarnings.length" class="mt-3">
        <Alert
          v-for="(w, i) in guardWarnings"
          :key="i"
          :message="w"
          class="mb-1"
          show-icon
          type="error"
        />
      </div>
    </div>

    <WatchdogBar
      :loading="acting === 'watchdog'"
      :watchdog="watchdog"
      @confirm="onKeepChange"
      @expired="reloadAll"
    />

    <Grid>
      <template #toolbar-actions>
        <Space>
          <Button
            v-if="canWrite"
            :disabled="!writable || !status.available"
            :loading="acting === 'add'"
            type="primary"
            @click="openCreate"
          >
            添加规则
          </Button>
        </Space>
      </template>

      <template #to="{ row }">
        <div class="flex items-center gap-1">
          <Tag>{{ toKindLabel((row as OpsApi.FirewallRule).toKind) }}</Tag>
          <span class="font-mono text-xs">
            {{ (row as OpsApi.FirewallRule).to }}
          </span>
          <Tag
            v-if="(row as OpsApi.FirewallRule).ipv6"
            color="blue"
          >
            v6
          </Tag>
        </div>
      </template>

      <template #action="{ row }">
        <Tag :color="actionColor((row as OpsApi.FirewallRule).action)">
          {{ actionLabel((row as OpsApi.FirewallRule).action) }}
        </Tag>
      </template>

      <template #direction="{ row }">
        {{ directionLabel((row as OpsApi.FirewallRule).direction) }}
      </template>

      <template #from="{ row }">
        <div class="font-mono text-xs">
          {{ (row as OpsApi.FirewallRule).from || 'Anywhere' }}
        </div>
        <div class="text-xs text-gray-400">
          {{ sourceKindLabel((row as OpsApi.FirewallRule).sourceKind) }}
        </div>
      </template>

      <template #comment="{ row }">
        <Tooltip :title="(row as OpsApi.FirewallRule).comment">
          <span class="line-clamp-2 text-xs">
            {{ (row as OpsApi.FirewallRule).comment || '-' }}
          </span>
        </Tooltip>
      </template>

      <template #provenance="{ row }">
        <Tag :color="provenanceMeta((row as OpsApi.FirewallRule).provenance).color">
          {{ provenanceMeta((row as OpsApi.FirewallRule).provenance).label }}
        </Tag>
      </template>

      <template #ops="{ row }">
        <Space :size="4">
          <Button
            v-if="canWrite"
            type="link"
            size="small"
            @click="openCopy(row as OpsApi.FirewallRule)"
          >
            复制
          </Button>
          <Button
            v-if="canWrite"
            danger
            type="link"
            size="small"
            @click="confirmDelete(row as OpsApi.FirewallRule)"
          >
            删除
          </Button>
        </Space>
      </template>
    </Grid>

    <RuleFormModal
      v-model:open="ruleOpen"
      :guard="guard"
      :source="ruleSource"
      @submit="onSubmitRule"
    />

    <ChangeHistoryDrawer
      v-model:open="historyOpen"
      :can-rollback="canRollback"
      @rollback="onRollback"
    />

    <!-- 原始规则：排障用，直接看 ufw status 原文 -->
    <Modal
      :open="rawOpen"
      :width="760"
      title="原始规则（ufw status）"
      @cancel="rawOpen = false"
      @ok="rawOpen = false"
    >
      <pre
        class="max-h-[520px] overflow-auto rounded bg-gray-50 p-3 font-mono text-xs leading-5"
      >{{ rawText || '读取中…' }}</pre>
    </Modal>

    <!-- 高危操作统一确认弹窗 -->
    <Modal
      :ok-button-props="{ disabled: !dangerReady }"
      :open="danger.open"
      :title="danger.title"
      ok-text="确认执行"
      ok-type="danger"
      @cancel="danger.open = false"
      @ok="runDanger"
    >
      <Alert
        v-if="danger.reason"
        :message="danger.reason"
        class="my-3"
        show-icon
        type="error"
      />
      <template v-if="danger.keyword">
        <div class="mb-1 text-xs text-gray-500">
          请键入 {{ danger.keyword }} 以确认
        </div>
        <Input v-model:value="dangerInput" :placeholder="danger.keyword" />
      </template>
      <Checkbox v-if="danger.needForce" v-model:checked="dangerForce" class="mt-3">
        我了解该规则由外部程序管理，仍要强制删除
      </Checkbox>
    </Modal>

    <!-- 默认策略 -->
    <Modal
      :ok-button-props="{ disabled: !policyReady }"
      :open="policyOpen"
      title="设置默认策略"
      @cancel="policyOpen = false"
      @ok="submitPolicy"
    >
      <Alert
        class="my-3"
        message="收紧默认入站策略会立即影响所有未显式放行的端口，属于高危操作。"
        show-icon
        type="warning"
      />
      <div class="mb-2">
        <div class="mb-1 text-xs text-gray-500">入站（incoming）</div>
        <Select
          v-model:value="policyForm.incoming"
          :options="POLICY_OPTIONS"
          allow-clear
          class="w-full"
          placeholder="不修改"
        />
      </div>
      <div class="mb-2">
        <div class="mb-1 text-xs text-gray-500">出站（outgoing）</div>
        <Select
          v-model:value="policyForm.outgoing"
          :options="POLICY_OPTIONS"
          allow-clear
          class="w-full"
          placeholder="不修改"
        />
      </div>
      <div class="mb-2">
        <div class="mb-1 text-xs text-gray-500">转发（routed）</div>
        <Select
          v-model:value="policyForm.routed"
          :options="POLICY_OPTIONS"
          allow-clear
          class="w-full"
          placeholder="不修改"
        />
      </div>
      <template v-if="policyKeyword">
        <div class="mb-1 text-xs text-gray-500">
          请键入 {{ policyKeyword }} 以确认
        </div>
        <Input v-model:value="policyConfirm" :placeholder="policyKeyword" />
      </template>
    </Modal>
  </Page>
</template>
