<script lang="ts" setup>
import type { OpsApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import { Alert, Button, message, Modal, Space, Table, Tag } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import { addFirewallRuleApi, deleteFirewallRuleApi, getFirewallStatusApi } from '#/api';

defineOptions({ name: 'OpsFirewall' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const status = ref<OpsApi.FirewallStatus>({
  active: false,
  backend: 'none',
  rules: [],
});

async function load() {
  loading.value = true;
  try {
    status.value = await getFirewallStatusApi();
  } finally {
    loading.value = false;
  }
}

// ==================== 新增规则 ====================
const [RuleForm, ruleFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'InputNumber',
      componentProps: { max: 65535, min: 1, style: 'width: 100%' },
      fieldName: 'port',
      label: '端口',
      rules: 'required',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: 'TCP', value: 'tcp' },
          { label: 'UDP', value: 'udp' },
        ],
      },
      defaultValue: 'tcp',
      fieldName: 'protocol',
      label: '协议',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '放行', value: 'allow' },
          { label: '拒绝', value: 'deny' },
        ],
      },
      defaultValue: 'allow',
      fieldName: 'action',
      label: '动作',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '可选，如 192.168.1.0/24（留空表示所有来源）' },
      fieldName: 'source',
      label: '来源',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [RuleModal, ruleModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await ruleFormApi.validate();
    if (!valid) {
      return;
    }
    const values = await ruleFormApi.getValues();
    const body: OpsApi.FirewallRuleBody = {
      action: values.action,
      port: values.port,
      protocol: values.protocol,
      source: values.source || undefined,
    };
    ruleModalApi.lock();
    try {
      await addFirewallRuleApi(body);
      message.success('规则已添加');
      ruleModalApi.close();
      await load();
    } finally {
      ruleModalApi.unlock();
    }
  },
});

function openAdd() {
  ruleFormApi.resetForm();
  ruleFormApi.setValues({ action: 'allow', protocol: 'tcp' });
  ruleModalApi.setData({ title: '添加防火墙规则' });
  ruleModalApi.open();
}

function confirmDelete(rule: OpsApi.FirewallRule) {
  const m = /^(\d+)\/(tcp|udp)$/.exec(rule.port);
  if (!m) {
    message.warning('该规则不支持从面板删除');
    return;
  }
  Modal.confirm({
    content: `确定删除规则 ${rule.port}（${rule.action}）？`,
    onOk: async () => {
      await deleteFirewallRuleApi({
        action: rule.action === 'deny' ? 'deny' : 'allow',
        port: Number(m[1]),
        protocol: m[2] as 'tcp' | 'udp',
      });
      message.success('规则已删除');
      await load();
    },
    title: '删除确认',
  });
}

onMounted(load);
</script>

<template>
  <Page class="flex flex-col">
    <Alert
      v-if="status.backend === 'none'"
      class="mb-3"
      message="未检测到可用的防火墙（ufw / firewalld），请先在系统层启用其中一个。"
      type="warning"
      show-icon
    />
    <Alert
      v-else-if="!status.active"
      class="mb-3"
      :message="`检测到 ${status.backend} 但当前未启用`"
      type="warning"
      show-icon
    />
    <Alert
      v-else
      class="mb-3"
      :message="`当前防火墙：${status.backend}（已启用）`"
      show-icon
      type="info"
    />

    <div class="mb-3 flex items-center gap-2">
      <span class="text-sm text-gray-500">共 {{ status.rules.length }} 条规则</span>
      <Button
        v-if="hasAccessByCodes(['ops:firewall:write'])"
        class="ml-auto"
        type="primary"
        @click="openAdd"
      >
        添加规则
      </Button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Table
        :data-source="status.rules"
        :loading="loading"
        :pagination="false"
        :row-key="(record: OpsApi.FirewallRule) => record.id"
        size="small"
      >
        <Table.Column data-index="id" title="编号" width="70" />
        <Table.Column key="port" title="端口/协议" width="150">
          <template #default="{ record }">
            <Tag color="blue">{{ record.port }}</Tag>
          </template>
        </Table.Column>
        <Table.Column key="action" title="动作" width="100">
          <template #default="{ record }">
            <Tag :color="record.action === 'allow' ? 'success' : 'error'">
              {{ record.action === 'allow' ? '放行' : '拒绝' }}
            </Tag>
          </template>
        </Table.Column>
        <Table.Column key="source" title="来源">
          <template #default="{ record }">
            {{ record.source }}
          </template>
        </Table.Column>
        <Table.Column key="action" title="操作" width="90" fixed="right">
          <template #default="{ record }">
            <Space>
              <Button
                v-if="hasAccessByCodes(['ops:firewall:write'])"
                danger
                size="small"
                type="link"
                @click="confirmDelete(record as OpsApi.FirewallRule)"
              >
                删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </div>

    <RuleModal class="w-[440px]" title="防火墙规则">
      <RuleForm />
    </RuleModal>
  </Page>
</template>
