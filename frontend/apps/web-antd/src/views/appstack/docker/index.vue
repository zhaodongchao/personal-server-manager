<script lang="ts" setup>
import type { AppstackApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
} from 'ant-design-vue';

import {
  containerActionApi,
  getContainerListApi,
  getImageListApi,
  pullImageApi,
} from '#/api';

defineOptions({ name: 'AppstackDocker' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const containers = ref<AppstackApi.ContainerInfo[]>([]);
const images = ref<AppstackApi.ImageInfo[]>([]);
const pullImage = ref('');

async function loadContainers() {
  loading.value = true;
  try {
    containers.value = await getContainerListApi();
  } catch {
    containers.value = [];
  } finally {
    loading.value = false;
  }
}

async function loadImages() {
  loading.value = true;
  try {
    images.value = await getImageListApi();
  } catch {
    images.value = [];
  } finally {
    loading.value = false;
  }
}

async function onContainerAction(record: AppstackApi.ContainerInfo, action: string) {
  const label: Record<string, string> = {
    remove: '删除',
    restart: '重启',
    start: '启动',
    stop: '停止',
  };
  if (action === 'remove') {
    Modal.confirm({
      content: `确定删除容器 ${record.name}（${record.id}）？容器文件系统将被移除。`,
      onOk: async () => {
        await containerActionApi(record.id, action);
        message.success('已删除');
        await loadContainers();
      },
      title: '删除确认',
    });
    return;
  }
  await containerActionApi(record.id, action);
  message.success(`已${label[action] ?? action}`);
  await loadContainers();
}

function confirmPull() {
  const image = pullImage.value.trim();
  if (!image) {
    message.warning('请输入镜像名');
    return;
  }
  Modal.confirm({
    content: `确定从远端拉取镜像 ${image}？可能需要数分钟。`,
    onOk: async () => {
      await pullImageApi(image);
      message.success('拉取完成');
      pullImage.value = '';
      await loadImages();
    },
    title: '拉取镜像',
  });
}

function formatSize(bytes: number) {
  if (!bytes) {
    return '-';
  }
  if (bytes > 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(ts: number) {
  if (!ts) {
    return '-';
  }
  return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false });
}

onMounted(() => {
  loadContainers();
  loadImages();
});
</script>

<template>
  <Page class="flex flex-col">
    <Tabs default-active-key="containers">
      <Tabs.TabPane key="containers" tab="容器">
        <div class="mb-3 flex items-center justify-end">
          <Button
            v-if="hasAccessByCodes(['appstack:docker:list'])"
            type="primary"
            @click="loadContainers"
          >
            刷新
          </Button>
        </div>
        <div class="min-h-0 overflow-auto rounded border">
          <Table
            :data-source="containers"
            :loading="loading"
            :pagination="false"
            :row-key="(record: AppstackApi.ContainerInfo) => record.id"
            :scroll="{ x: 860 }"
            size="small"
          >
            <Table.Column data-index="id" title="容器ID" width="130" />
            <Table.Column key="name" title="名称" width="180">
              <template #default="{ record }">
                <span class="font-medium">{{ record.name }}</span>
              </template>
            </Table.Column>
            <Table.Column key="image" title="镜像" width="220">
              <template #default="{ record }">
                <span class="break-all font-mono text-xs">{{ record.image }}</span>
              </template>
            </Table.Column>
            <Table.Column key="state" title="状态" width="110">
              <template #default="{ record }">
                <Tag :color="record.state === 'running' ? 'success' : 'default'">
                  {{ record.status }}
                </Tag>
              </template>
            </Table.Column>
            <Table.Column key="ports" title="端口">
              <template #default="{ record }">
                <span class="break-all font-mono text-xs">{{ record.ports }}</span>
              </template>
            </Table.Column>
            <Table.Column key="action" title="操作" width="210" fixed="right">
              <template #default="{ record }">
                <Space :size="2" wrap>
                  <Button
                    v-if="record.state !== 'running'"
                    size="small"
                    type="link"
                    @click="onContainerAction(record as AppstackApi.ContainerInfo, 'start')"
                  >
                    启动
                  </Button>
                  <Button
                    v-if="record.state === 'running'"
                    size="small"
                    type="link"
                    @click="onContainerAction(record as AppstackApi.ContainerInfo, 'stop')"
                  >
                    停止
                  </Button>
                  <Button
                    v-if="record.state === 'running'"
                    size="small"
                    type="link"
                    @click="onContainerAction(record as AppstackApi.ContainerInfo, 'restart')"
                  >
                    重启
                  </Button>
                  <Button
                    danger
                    size="small"
                    type="link"
                    @click="onContainerAction(record as AppstackApi.ContainerInfo, 'remove')"
                  >
                    删除
                  </Button>
                </Space>
              </template>
            </Table.Column>
          </Table>
        </div>
      </Tabs.TabPane>

      <Tabs.TabPane key="images" tab="镜像">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Input
            v-model:value="pullImage"
            class="w-72"
            placeholder="如 nginx:alpine / mysql:8.0"
            @press-enter="confirmPull"
          />
          <Button
            v-if="hasAccessByCodes(['appstack:docker:manage'])"
            type="primary"
            @click="confirmPull"
          >
            拉取镜像
          </Button>
          <Button class="ml-auto" type="primary" @click="loadImages">刷新</Button>
        </div>
        <div class="min-h-0 overflow-auto rounded border">
          <Table
            :data-source="images"
            :loading="loading"
            :pagination="false"
            :row-key="(record: AppstackApi.ImageInfo) => record.id"
            :scroll="{ x: 720 }"
            size="small"
          >
            <Table.Column data-index="id" title="镜像ID" width="130" />
            <Table.Column key="tag" title="标签">
              <template #default="{ record }">
                <span class="break-all font-mono text-xs">{{ record.tag }}</span>
              </template>
            </Table.Column>
            <Table.Column key="size" title="大小" width="110">
              <template #default="{ record }">
                {{ formatSize(record.size) }}
              </template>
            </Table.Column>
            <Table.Column key="created" title="创建时间" width="170">
              <template #default="{ record }">
                {{ formatTime(record.created) }}
              </template>
            </Table.Column>
          </Table>
        </div>
      </Tabs.TabPane>
    </Tabs>

    <Alert
      v-if="containers.length === 0 && !loading"
      class="mt-3"
      message="未检测到 Docker 容器，或 Docker 服务不可用。"
      show-icon
      type="info"
    />
  </Page>
</template>
