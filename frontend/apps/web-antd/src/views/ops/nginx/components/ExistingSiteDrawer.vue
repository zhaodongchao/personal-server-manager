<script lang="ts" setup>
/**
 * 宝塔既有站点（只读）。
 *
 * <p>解析 `nginx -T` 的已加载 server 块，列出宝塔 vhost 下（非面板托管目录）
 * 的既有站点，供参考。面板不接管这些站点：不改动、只读展示；如需纳入管理，
 * 请用「新建站点」按同域名重建为面板托管站点。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import { ref, watch } from 'vue';

import { Alert, Drawer, Empty, Spin, Table, Tag } from 'ant-design-vue';

import { getNginxExistingApi } from '#/api';

defineOptions({ name: 'OpsNginxExistingDrawer' });

const props = defineProps<{
  open: boolean;
  instanceId?: string;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
}>();

interface ExistingSite {
  confFile?: string;
  serverNames?: string[];
  listens?: string[];
  ssl?: boolean;
  root?: string;
  proxyPass?: string;
}

const loading = ref(false);
const rows = ref<ExistingSite[]>([]);

async function load() {
  loading.value = true;
  try {
    const res = (await getNginxExistingApi(props.instanceId)) ?? [];
    rows.value = res.map((m) => ({
      confFile: m.confFile ? String(m.confFile) : undefined,
      serverNames: Array.isArray(m.serverNames) ? m.serverNames.map(String) : [],
      listens: Array.isArray(m.listens) ? m.listens.map(String) : [],
      ssl: !!m.ssl,
      root: m.root ? String(m.root) : undefined,
      proxyPass: m.proxyPass ? String(m.proxyPass) : undefined,
    }));
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) void load();
  },
);

const columns = [
  { dataIndex: 'serverNames', title: '域名', width: 220 },
  { dataIndex: 'listens', title: '监听', width: 140 },
  { dataIndex: 'type', title: '类型', width: 100 },
  { dataIndex: 'target', title: '目标 / 根目录' },
  { dataIndex: 'confFile', title: '配置文件' },
];
</script>

<template>
  <Drawer
    :open="open"
    :width="820"
    title="宝塔既有站点（只读）"
    @close="emit('update:open', false)"
  >
    <Alert
      class="mb-3"
      message="以下为 nginx 已加载的既有 server 块（来自宝塔 vhost），面板仅只读展示、不会改动。"
      show-icon
      type="info"
    />
    <Spin :spinning="loading">
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        :row-key="(r: ExistingSite) => (r.confFile ?? '') + (r.serverNames?.[0] ?? '')"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'serverNames'">
            <div class="flex flex-wrap gap-1">
              <Tag v-for="(n, i) in (record as ExistingSite).serverNames" :key="i">
                {{ n }}
              </Tag>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'listens'">
            <span class="font-mono text-xs">
              {{ (record as ExistingSite).listens?.join(', ') || '-' }}
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'type'">
            <Tag :color="(record as ExistingSite).ssl ? 'green' : 'default'">
              {{ (record as ExistingSite).ssl ? 'HTTPS' : 'HTTP' }}
            </Tag>
          </template>
          <template v-else-if="column.dataIndex === 'target'">
            <span class="break-all font-mono text-xs">
              {{ (record as ExistingSite).proxyPass || (record as ExistingSite).root || '-' }}
            </span>
          </template>
          <template v-else-if="column.dataIndex === 'confFile'">
            <span class="break-all font-mono text-xs">
              {{ (record as ExistingSite).confFile }}
            </span>
          </template>
        </template>
      </Table>
      <Empty
        v-if="rows.length === 0 && !loading"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="未发现既有站点"
      />
    </Spin>
  </Drawer>
</template>
