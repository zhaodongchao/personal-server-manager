<script lang="ts" setup>
/**
 * 执行器管理抽屉。
 *
 * <p>「执行器」是从 xxl-job 借来的第一个概念：它把「谁来跑」从任务里拆出来，因此同一套
 * 任务定义可以整体切到另一台执行机器上。本版本只有两种执行器：
 * <ul>
 *   <li>{@code BUILTIN} —— 面板自身进程，<b>不可删除、不可停用</b>（删掉它等于把面板
 *       现有的内置任务全部悬空），后端用 6035 守住；</li>
 *   <li>{@code HTTP} —— 外部执行器，本版本只做接入与连通性探测，不做注册中心与心跳：
 *       面板按任务里的参数<b>主动</b>请求 {@code {base_url}/run}，同步拿结果。</li>
 * </ul>
 *
 * <p>这里刻意不做「心跳保活」的假象：面板是单机运维工具，让人以为有分布式调度能力，
 * 比老实说自己只是「定时 HTTP 客户端」危险得多。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
import type { JobApi } from '#/api';

import { h, ref, watch } from 'vue';

import { useAccess } from '@vben/access';

import {
  Button,
  Drawer,
  Form,
  Input,
  message,
  Modal,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  createExecutorApi,
  deleteExecutorApi,
  getExecutorListApi,
  testExecutorApi,
  updateExecutorApi,
} from '#/api';

defineOptions({ name: 'AppstackJobExecutorDrawer' });

const props = defineProps<{ open: boolean }>();

const emit = defineEmits<{
  'update:open': [boolean];
  closed: [];
}>();

const { hasAccessByCodes } = useAccess();

const STATUS_META: Record<string, { color: string; label: string }> = {
  AVAILABLE: { color: 'success', label: '可用' },
  DISABLED: { color: 'default', label: '已停用' },
  UNREACHABLE: { color: 'error', label: '不可达' },
};

const loading = ref(false);
const list = ref<JobApi.Executor[]>([]);
const testingId = ref<null | string>(null);

const formOpen = ref(false);
const saving = ref(false);
const editing = ref<JobApi.Executor | null>(null);
const form = ref<JobApi.ExecutorBody>({
  appName: '',
  authToken: '',
  baseUrl: '',
  executorName: '',
  remark: '',
  status: 'AVAILABLE',
  type: 'HTTP',
});

watch(
  () => props.open,
  (open) => {
    if (open) void load();
  },
);

async function load() {
  loading.value = true;
  try {
    list.value = await getExecutorListApi();
  } catch {
    list.value = [];
  } finally {
    loading.value = false;
  }
}

function close() {
  emit('update:open', false);
  emit('closed');
}

function openCreate() {
  editing.value = null;
  form.value = {
    appName: '',
    authToken: '',
    baseUrl: '',
    executorName: '',
    remark: '',
    status: 'AVAILABLE',
    type: 'HTTP',
  };
  formOpen.value = true;
}

function openEdit(record: JobApi.Executor) {
  editing.value = record;
  form.value = {
    appName: record.appName,
    authToken: '',
    baseUrl: record.baseUrl ?? '',
    executorName: record.executorName ?? '',
    remark: record.remark ?? '',
    status: record.status,
    type: record.type,
  };
  formOpen.value = true;
}

async function submit() {
  const body = form.value;
  if (!body.appName?.trim()) {
    message.warning('请填写 AppName');
    return;
  }
  if (!/^[A-Za-z0-9_-]+$/.test(body.appName.trim())) {
    message.warning('AppName 只允许字母、数字、下划线、短横线');
    return;
  }
  if (body.type === 'HTTP' && !body.baseUrl?.trim()) {
    message.warning('外部执行器必须填写 base_url');
    return;
  }
  saving.value = true;
  try {
    const payload: JobApi.ExecutorBody = {
      ...body,
      appName: body.appName.trim(),
      // 留空表示「不修改令牌」，避免编辑时误清
      authToken: body.authToken?.trim() ? body.authToken.trim() : undefined,
      baseUrl: body.baseUrl?.trim() || undefined,
      executorName: body.executorName?.trim() || undefined,
      remark: body.remark?.trim() || undefined,
    };
    if (editing.value?.id) {
      await updateExecutorApi(editing.value.id, payload);
      message.success('保存成功');
    } else {
      await createExecutorApi(payload);
      message.success('创建成功');
    }
    formOpen.value = false;
    await load();
  } catch {
    // 错误提示由请求拦截器统一给出
  } finally {
    saving.value = false;
  }
}

async function doTest(record: JobApi.Executor) {
  if (!record.id) return;
  testingId.value = record.id;
  try {
    const res = await testExecutorApi(record.id);
    if (res?.status === 'AVAILABLE') {
      message.success(`连通性正常：${res.executorName || res.appName}`);
    } else {
      message.warning(res?.lastError || `探测结果：${res?.status}`);
    }
    await load();
  } catch {
    // 错误提示由请求拦截器统一给出
  } finally {
    testingId.value = null;
  }
}

function doDelete(record: JobApi.Executor) {
  if (!record.id || record.type === 'BUILTIN') return;
  const confirm = `DELETE EXECUTOR ${record.appName}`;
  let input = '';
  Modal.confirm({
    content: h('div', { class: 'space-y-2' }, [
      h(
        'div',
        { class: 'text-sm' },
        `确定删除执行器「${record.appName}」？若仍有任务引用它，删除会被拒绝。`,
      ),
      h('div', { class: 'text-xs text-gray-500' }, [
        '请输入确认关键字：',
        h('span', { class: 'font-mono font-semibold text-red-600' }, confirm),
      ]),
      h(Input, {
        'onUpdate:value': (v: string) => {
          input = v;
        },
        placeholder: confirm,
      }),
    ]),
    onOk: async () => {
      if (input.trim() !== confirm) {
        message.error(`确认关键字不匹配，请输入 ${confirm}`);
        throw new Error('confirm mismatch');
      }
      await deleteExecutorApi(record.id!, confirm);
      message.success('删除成功');
      await load();
    },
    okButtonProps: { danger: true },
    title: '删除执行器确认',
  });
}
</script>

<template>
  <Drawer :open="open" :width="900" title="执行器管理" @close="close">
    <div class="mb-3 flex items-center gap-2">
      <span class="text-xs text-gray-500">
        执行器决定任务「谁来跑」；内置执行器在面板进程内执行，不可删除。
      </span>
      <Button
        v-if="hasAccessByCodes(['appstack:job:executor'])"
        class="ml-auto"
        size="small"
        type="primary"
        @click="openCreate"
      >
        新增执行器
      </Button>
    </div>

    <Table
      :data-source="list"
      :loading="loading"
      :pagination="false"
      :row-key="(record: JobApi.Executor) => record.id"
      :scroll="{ x: 860 }"
      size="small"
    >
      <Table.Column key="appName" title="AppName" :width="180">
        <template #default="{ record }">
          <div class="font-mono font-medium">{{ record.appName }}</div>
          <div class="text-xs text-gray-500">{{ record.executorName || '-' }}</div>
        </template>
      </Table.Column>
      <Table.Column key="type" title="类型" :width="100">
        <template #default="{ record }">
          <Tag :color="record.type === 'BUILTIN' ? 'green' : 'blue'">
            {{ record.type }}
          </Tag>
        </template>
      </Table.Column>
      <Table.Column key="baseUrl" title="base_url" :width="220">
        <template #default="{ record }">
          <span class="font-mono text-xs">{{ record.baseUrl || 'local' }}</span>
        </template>
      </Table.Column>
      <Table.Column key="status" title="状态" :width="130">
        <template #default="{ record }">
          <Tag :color="(STATUS_META[record.status] ?? {}).color || 'default'">
            {{ (STATUS_META[record.status] ?? {}).label || record.status }}
          </Tag>
          <span v-if="(record.failStreak ?? 0) > 0" class="text-xs text-red-500">
            连败 {{ record.failStreak }}
          </span>
        </template>
      </Table.Column>
      <Table.Column key="lastError" title="最近错误" ellipsis>
        <template #default="{ record }">
          <span class="text-xs text-gray-500">{{ record.lastError || '-' }}</span>
        </template>
      </Table.Column>
      <Table.Column key="action" title="操作" :width="210" fixed="right">
        <template #default="{ record }">
          <Space :size="2">
            <Button
              :loading="testingId === record.id"
              size="small"
              type="link"
              @click="doTest(record as JobApi.Executor)"
            >
              探测
            </Button>
            <Button
              v-if="hasAccessByCodes(['appstack:job:executor'])"
              size="small"
              type="link"
              @click="openEdit(record as JobApi.Executor)"
            >
              编辑
            </Button>
            <Button
              v-if="
                hasAccessByCodes(['appstack:job:executor']) &&
                record.type !== 'BUILTIN'
              "
              danger
              size="small"
              type="link"
              @click="doDelete(record as JobApi.Executor)"
            >
              删除
            </Button>
          </Space>
        </template>
      </Table.Column>
    </Table>

    <Modal
      v-model:open="formOpen"
      :confirm-loading="saving"
      :title="editing ? `编辑执行器：${editing.appName}` : '新增执行器'"
      @ok="submit"
    >
      <Form layout="vertical">
        <Form.Item label="AppName" required>
          <Input
            v-model:value="form.appName"
            :disabled="!!editing"
            placeholder="如 executor-node1"
          />
        </Form.Item>
        <Form.Item label="显示名">
          <Input v-model:value="form.executorName" placeholder="如 备用执行节点" />
        </Form.Item>
        <Form.Item label="类型" required>
          <Input :value="form.type" disabled />
          <div class="mt-1 text-xs text-gray-500">
            本版本只支持新增 HTTP 外部执行器（内置执行器由迁移脚本播种，不可新增）。
          </div>
        </Form.Item>
        <Form.Item label="base_url" required>
          <Input
            v-model:value="form.baseUrl"
            placeholder="如 http://10.0.0.9:9999"
          />
          <div class="mt-1 text-xs text-gray-500">
            面板会同步 POST {base_url}/run，请求体含 executorHandler / executorParams /
            executorBlockStrategy / executorTimeout / logId。
          </div>
        </Form.Item>
        <Form.Item label="认证令牌">
          <Input
            v-model:value="form.authToken"
            placeholder="留空表示不修改（新建时留空即无认证）"
          />
        </Form.Item>
        <Form.Item label="备注">
          <Input v-model:value="form.remark" />
        </Form.Item>
      </Form>
    </Modal>
  </Drawer>
</template>
