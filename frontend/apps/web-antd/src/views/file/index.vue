<script lang="ts" setup>
import type { FileApi } from '#/api';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';

import {
  Breadcrumb,
  Button,
  Drawer,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  compressFileApi,
  createDirApi,
  deleteFileApi,
  downloadFileApi,
  emptyRecycleApi,
  extractFileApi,
  getFileContentApi,
  getFileListApi,
  getFileRootsApi,
  getRecyclePageApi,
  moveFileApi,
  purgeRecycleApi,
  renameFileApi,
  restoreRecycleApi,
  setFilePermApi,
  updateFileContentApi,
  uploadFileApi,
} from '#/api';

defineOptions({ name: 'FileManager' });

const { hasAccessByCodes } = useAccess();

// ==================== 主列表 ====================
const roots = ref<FileApi.FileEntry[]>([]);
const files = ref<FileApi.FileEntry[]>([]);
const currentPath = ref('');
const loading = ref(false);
const selected = ref<FileApi.FileEntry | null>(null);
const fileInput = ref<HTMLInputElement>();

const breadcrumbs = computed(() => {
  if (!currentPath.value) {
    return [];
  }
  const segments = currentPath.value.split('/').filter(Boolean);
  const items: { label: string; path: string }[] = [{ label: '/', path: '/' }];
  let acc = '';
  segments.forEach((seg) => {
    acc += '/' + seg;
    items.push({ label: seg, path: acc });
  });
  return items;
});

async function loadList(path: string) {
  loading.value = true;
  try {
    files.value = await getFileListApi(path);
  } finally {
    loading.value = false;
  }
}

function gotoPath(path: string) {
  currentPath.value = path;
  selected.value = null;
  loadList(path);
}

function refresh() {
  loadList(currentPath.value);
}

function goParent() {
  const idx = currentPath.value.lastIndexOf('/');
  gotoPath(idx > 0 ? currentPath.value.slice(0, idx) : '/');
}

function rowEvents(record: FileApi.FileEntry) {
  return {
    onDblclick: () => onRowDblClick(record),
  };
}

function onRowDblClick(row: FileApi.FileEntry) {
  if (row.dir) {
    gotoPath(row.path);
  } else if (row.text) {
    openEditor(row);
  }
}

onMounted(async () => {
  roots.value = await getFileRootsApi();
  if (roots.value.length > 0) {
    gotoPath(roots.value[0]?.path ?? '/');
  }
});

// ==================== 新建目录 ====================
const newDirVisible = ref(false);
const newDirName = ref('');

async function confirmNewDir() {
  const name = newDirName.value.trim();
  if (!name) {
    message.warning('请输入目录名');
    return;
  }
  await createDirApi(`${currentPath.value}/${name}`);
  message.success('创建成功');
  newDirVisible.value = false;
  newDirName.value = '';
  refresh();
}

// ==================== 重命名 ====================
const renameVisible = ref(false);
const renameName = ref('');

function openRename(row: FileApi.FileEntry) {
  selected.value = row;
  renameName.value = row.name;
  renameVisible.value = true;
}

async function confirmRename() {
  const name = renameName.value.trim();
  if (!name || !selected.value) {
    message.warning('请输入新名称');
    return;
  }
  await renameFileApi(selected.value.path, name);
  message.success('重命名成功');
  renameVisible.value = false;
  refresh();
}

// ==================== 移动 ====================
const moveVisible = ref(false);
const moveTarget = ref('');

function openMove(row: FileApi.FileEntry) {
  selected.value = row;
  moveTarget.value = currentPath.value;
  moveVisible.value = true;
}

async function confirmMove() {
  const target = moveTarget.value.trim();
  if (!target || !selected.value) {
    message.warning('请输入目标目录');
    return;
  }
  await moveFileApi(selected.value.path, target);
  message.success('移动成功');
  moveVisible.value = false;
  refresh();
}

// ==================== 修改权限 ====================
const PERM_PRESETS = [
  { label: '644 (rw-r--r--)', value: '644' },
  { label: '755 (rwxr-xr-x)', value: '755' },
  { label: '600 (rw-------)', value: '600' },
  { label: '700 (rwx------)', value: '700' },
  { label: '777 (rwxrwxrwx)', value: '777' },
];

const permVisible = ref(false);
const permMode = ref('644');

function symbolicToOctal(perms: string) {
  if (!perms || perms.length < 9) {
    return '644';
  }
  const map: Record<string, string> = {
    '---': '0',
    '--x': '1',
    '-w-': '2',
    '-wx': '3',
    'r--': '4',
    'r-x': '5',
    'rw-': '6',
    'rwx': '7',
  };
  let oct = '';
  for (let i = 0; i < 3; i++) {
    const seg = perms.slice(i * 3, i * 3 + 3);
    oct += map[seg] ?? '0';
  }
  return oct;
}

function openPerm(row: FileApi.FileEntry) {
  selected.value = row;
  permMode.value = row.perms ? symbolicToOctal(row.perms) : '644';
  permVisible.value = true;
}

async function confirmPerm() {
  const mode = permMode.value.trim();
  if (!/^[0-7]{3}$/.test(mode) || !selected.value) {
    message.warning('请输入三位八进制权限，如 755');
    return;
  }
  await setFilePermApi(selected.value.path, mode);
  message.success('权限已修改');
  permVisible.value = false;
  refresh();
}

// ==================== 在线编辑 ====================
const editorVisible = ref(false);
const editorPath = ref('');
const editorContent = ref('');
const editorLoading = ref(false);
const editorSaving = ref(false);

async function openEditor(row: FileApi.FileEntry) {
  editorPath.value = row.path;
  editorContent.value = '';
  editorVisible.value = true;
  editorLoading.value = true;
  try {
    editorContent.value = await getFileContentApi(row.path);
  } finally {
    editorLoading.value = false;
  }
}

async function saveEditor() {
  if (!editorPath.value) {
    return;
  }
  editorSaving.value = true;
  try {
    await updateFileContentApi(editorPath.value, editorContent.value);
    message.success('保存成功');
  } finally {
    editorSaving.value = false;
  }
}

// ==================== 压缩 / 解压 ====================
const compressVisible = ref(false);
const compressFormat = ref<'zip' | 'tar.gz'>('zip');

function openCompress(row: FileApi.FileEntry) {
  selected.value = row;
  compressFormat.value = 'zip';
  compressVisible.value = true;
}

async function confirmCompress() {
  if (!selected.value) {
    return;
  }
  await compressFileApi(selected.value.path, compressFormat.value);
  message.success('压缩完成');
  compressVisible.value = false;
  refresh();
}

const extractVisible = ref(false);
const extractTarget = ref('');

function openExtract(row: FileApi.FileEntry) {
  selected.value = row;
  extractTarget.value = currentPath.value;
  extractVisible.value = true;
}

async function confirmExtract() {
  const target = extractTarget.value.trim();
  if (!target || !selected.value) {
    message.warning('请输入目标目录');
    return;
  }
  await extractFileApi(selected.value.path, target);
  message.success('解压完成');
  extractVisible.value = false;
  refresh();
}

function isArchive(name: string) {
  return /\.(zip|tar\.gz|tgz)$/i.test(name);
}

// ==================== 删除 ====================
function confirmDelete(row: FileApi.FileEntry) {
  Modal.confirm({
    content: `确定将「${row.name}」移入回收站？可随时还原。`,
    onOk: async () => {
      await deleteFileApi(row.path);
      message.success('已移入回收站');
      refresh();
    },
    title: '删除确认',
  });
}

// ==================== 上传 ====================
function triggerUpload() {
  fileInput.value?.click();
}

async function onUploadChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const selectedFiles = Array.from(input.files ?? []);
  input.value = '';
  if (selectedFiles.length === 0) {
    return;
  }
  const count = await uploadFileApi(currentPath.value, selectedFiles);
  message.success(`上传成功 ${count} 个文件`);
  refresh();
}

// ==================== 回收站 ====================
const recycleOpen = ref(false);
const recycleRows = ref<FileApi.RecycleItem[]>([]);
const recycleLoading = ref(false);
const recycleTotal = ref(0);
const recyclePage = reactive({ pageNum: 1, pageSize: 10 });

async function loadRecycle() {
  recycleLoading.value = true;
  try {
    const res = await getRecyclePageApi({ ...recyclePage });
    recycleRows.value = res.records ?? [];
    recycleTotal.value = res.total ?? 0;
  } finally {
    recycleLoading.value = false;
  }
}

function openRecycle() {
  recycleOpen.value = true;
  recyclePage.pageNum = 1;
  loadRecycle();
}

function onRecyclePageChange(page: number, pageSize: number) {
  recyclePage.pageNum = page;
  recyclePage.pageSize = pageSize;
  loadRecycle();
}

async function confirmRestore(row: FileApi.RecycleItem) {
  await restoreRecycleApi(row.id);
  message.success('还原成功');
  loadRecycle();
  refresh();
}

async function confirmPurge(row: FileApi.RecycleItem) {
  Modal.confirm({
    content: `彻底删除「${row.fileName}」？此操作不可恢复！`,
    onOk: async () => {
      await purgeRecycleApi(row.id);
      message.success('已彻底删除');
      loadRecycle();
    },
    title: '彻底删除确认',
  });
}

async function confirmEmptyRecycle() {
  Modal.confirm({
    content: '确定清空回收站？所有文件将被彻底删除，不可恢复！',
    onOk: async () => {
      await emptyRecycleApi();
      message.success('回收站已清空');
      loadRecycle();
    },
    title: '清空回收站',
  });
}

// ==================== 格式化 ====================
function formatBytes(bytes: number) {
  if (!bytes) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function formatTime(ms: number) {
  if (!ms) {
    return '-';
  }
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
</script>

<template>
  <Page class="flex flex-col">
    <!-- 工具条 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Select
        :options="
          roots.map((r) => ({ label: r.name || r.path, value: r.path }))
        "
        :value="currentPath"
        class="w-40"
        placeholder="根目录"
        @change="(value) => gotoPath(String(value))"
      />
      <Button @click="goParent">上级</Button>
      <Breadcrumb class="min-w-0 flex-1">
        <Breadcrumb.Item v-for="item in breadcrumbs" :key="item.path">
          <a @click="gotoPath(item.path)">{{ item.label }}</a>
        </Breadcrumb.Item>
      </Breadcrumb>
      <Space>
        <Button @click="refresh">刷新</Button>
        <Button
          v-if="hasAccessByCodes(['file:mkdir'])"
          type="primary"
          @click="
            newDirName = '';
            newDirVisible = true;
          "
        >
          新建文件夹
        </Button>
        <Button v-if="hasAccessByCodes(['file:upload'])" @click="triggerUpload">
          上传
        </Button>
        <Button @click="openRecycle">回收站</Button>
      </Space>
    </div>

    <!-- 文件表格 -->
    <div class="min-h-0 flex-1 overflow-auto rounded border">
      <Spin :spinning="loading">
        <Table
          :custom-row="rowEvents"
          :data-source="files"
          :pagination="false"
          :row-class-name="
            (record: FileApi.FileEntry) =>
              selected?.path === record.path ? 'selected-row' : ''
          "
          :row-key="(record: FileApi.FileEntry) => record.path"
          :size="'small'"
          @row-click="(record: FileApi.FileEntry) => (selected = record)"
        >
          <Table.Column key="name" title="名称" width="320">
            <template #default="{ record }">
              <span class="flex items-center gap-2">
                <IconifyIcon
                  :icon="
                    record.dir
                      ? 'lucide:folder'
                      : record.text
                        ? 'lucide:file-text'
                        : 'lucide:file'
                  "
                  :class="record.dir ? 'text-amber-500' : 'text-sky-500'"
                />
                <span class="truncate">{{ record.name }}</span>
              </span>
            </template>
          </Table.Column>
          <Table.Column key="size" title="大小" width="100">
            <template #default="{ record }">
              {{ record.dir ? '-' : formatBytes(record.size) }}
            </template>
          </Table.Column>
          <Table.Column key="time" title="修改时间" width="170">
            <template #default="{ record }">
              {{ formatTime(record.lastModified) }}
            </template>
          </Table.Column>
          <Table.Column key="perms" title="权限" width="110">
            <template #default="{ record }">
              <Tag v-if="record.perms">{{ record.perms }}</Tag>
              <span v-else>-</span>
            </template>
          </Table.Column>
          <Table.Column key="action" title="操作" width="380">
            <template #default="{ record }">
              <Space :size="2" wrap>
                <Button
                  v-if="record.text && hasAccessByCodes(['file:edit'])"
                  size="small"
                  type="link"
                  @click.stop="openEditor(record as FileApi.FileEntry)"
                >
                  编辑
                </Button>
                <Button
                  v-if="hasAccessByCodes(['file:edit'])"
                  size="small"
                  type="link"
                  @click.stop="openRename(record as FileApi.FileEntry)"
                >
                  重命名
                </Button>
                <Button
                  v-if="hasAccessByCodes(['file:edit'])"
                  size="small"
                  type="link"
                  @click.stop="openMove(record as FileApi.FileEntry)"
                >
                  移动
                </Button>
                <Button
                  v-if="hasAccessByCodes(['file:perm'])"
                  size="small"
                  type="link"
                  @click.stop="openPerm(record as FileApi.FileEntry)"
                >
                  权限
                </Button>
                <Button
                  v-if="hasAccessByCodes(['file:compress'])"
                  size="small"
                  type="link"
                  @click.stop="openCompress(record as FileApi.FileEntry)"
                >
                  压缩
                </Button>
                <Button
                  v-if="
                    hasAccessByCodes(['file:compress']) &&
                    isArchive(record.name)
                  "
                  size="small"
                  type="link"
                  @click.stop="openExtract(record as FileApi.FileEntry)"
                >
                  解压
                </Button>
                <Button
                  v-if="hasAccessByCodes(['file:list'])"
                  size="small"
                  type="link"
                  @click.stop="downloadFileApi(record.path)"
                >
                  下载
                </Button>
                <Popconfirm
                  title="确定删除？"
                  @confirm="confirmDelete(record as FileApi.FileEntry)"
                >
                  <Button
                    v-if="hasAccessByCodes(['file:delete'])"
                    danger
                    size="small"
                    type="link"
                  >
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            </template>
          </Table.Column>
        </Table>
      </Spin>
    </div>

    <!-- 隐藏文件输入 -->
    <input
      ref="fileInput"
      class="hidden"
      multiple
      type="file"
      @change="onUploadChange"
    />

    <!-- 新建文件夹 -->
    <Modal
      :open="newDirVisible"
      title="新建文件夹"
      @cancel="newDirVisible = false"
      @ok="confirmNewDir"
    >
      <Form layout="vertical">
        <Form.Item label="目录名">
          <Input
            v-model:value="newDirName"
            placeholder="例如：backup"
            @keyup.enter="confirmNewDir"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 重命名 -->
    <Modal
      :open="renameVisible"
      title="重命名"
      @cancel="renameVisible = false"
      @ok="confirmRename"
    >
      <Form layout="vertical">
        <Form.Item label="新名称">
          <Input
            v-model:value="renameName"
            @keyup.enter="confirmRename"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 移动 -->
    <Modal
      :open="moveVisible"
      title="移动到"
      @cancel="moveVisible = false"
      @ok="confirmMove"
    >
      <Form layout="vertical">
        <Form.Item
          help="填写白名单内的绝对路径，目标必须是已存在的目录"
          label="目标目录"
        >
          <Input
            v-model:value="moveTarget"
            placeholder="/www/data"
            @keyup.enter="confirmMove"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 修改权限 -->
    <Modal
      :open="permVisible"
      title="修改权限"
      @cancel="permVisible = false"
      @ok="confirmPerm"
    >
      <Form layout="vertical">
        <Form.Item label="常用权限">
          <Radio.Group
            v-model:value="permMode"
            :options="PERM_PRESETS"
          />
        </Form.Item>
        <Form.Item label="自定义（三位八进制）">
          <Input
            v-model:value="permMode"
            :maxlength="3"
            placeholder="755"
            @keyup.enter="confirmPerm"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 在线编辑 -->
    <Modal
      :open="editorVisible"
      :width="860"
      title="在线编辑"
      @cancel="editorVisible = false"
      @ok="saveEditor"
    >
      <Spin :spinning="editorLoading">
        <div class="mb-2 break-all text-xs text-gray-500">
          {{ editorPath }}
        </div>
        <textarea
          v-model="editorContent"
          class="h-[480px] w-full resize-none rounded border p-3 font-mono text-sm"
          spellcheck="false"
        ></textarea>
      </Spin>
    </Modal>

    <!-- 压缩 -->
    <Modal
      :open="compressVisible"
      title="压缩"
      @cancel="compressVisible = false"
      @ok="confirmCompress"
    >
      <Form layout="vertical">
        <Form.Item label="压缩格式">
          <Radio.Group
            v-model:value="compressFormat"
            :options="[
              { label: 'zip', value: 'zip' },
              { label: 'tar.gz', value: 'tar.gz' },
            ]"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 解压 -->
    <Modal
      :open="extractVisible"
      title="解压到"
      @cancel="extractVisible = false"
      @ok="confirmExtract"
    >
      <Form layout="vertical">
        <Form.Item label="目标目录">
          <Input
            v-model:value="extractTarget"
            @keyup.enter="confirmExtract"
          />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 回收站 -->
    <Drawer
      :open="recycleOpen"
      :width="720"
      title="回收站"
      @close="recycleOpen = false"
    >
      <div class="mb-2 flex justify-between">
        <span class="text-sm text-gray-500">
          共 {{ recycleTotal }} 条记录，过期自动清理
        </span>
        <Button
          v-if="recycleTotal > 0"
          danger
          size="small"
          @click="confirmEmptyRecycle"
        >
          清空
        </Button>
      </div>
      <Table
        :data-source="recycleRows"
        :loading="recycleLoading"
        :pagination="{
          current: recyclePage.pageNum,
          pageSize: recyclePage.pageSize,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          total: recycleTotal,
          onChange: onRecyclePageChange,
        }"
        :row-key="(record: FileApi.RecycleItem) => record.id"
        size="small"
      >
        <Table.Column data-index="fileName" title="文件名" width="220" />
        <Table.Column key="origin" title="原位置">
          <template #default="{ record }">
            <span class="break-all text-xs">{{ record.originPath }}</span>
          </template>
        </Table.Column>
        <Table.Column key="size" title="大小" width="90">
          <template #default="{ record }">
            {{ record.isDir === 1 ? '-' : formatBytes(record.size) }}
          </template>
        </Table.Column>
        <Table.Column key="time" title="删除时间" width="160">
          <template #default="{ record }">
            {{ formatTime(new Date(record.createdAt).getTime()) }}
          </template>
        </Table.Column>
        <Table.Column key="op" title="操作" width="150">
          <template #default="{ record }">
            <Space>
              <Button
                size="small"
                type="link"
                @click="confirmRestore(record as FileApi.RecycleItem)"
              >
                还原
              </Button>
              <Button
                danger
                size="small"
                type="link"
                @click="confirmPurge(record as FileApi.RecycleItem)"
              >
                彻底删除
              </Button>
            </Space>
          </template>
        </Table.Column>
      </Table>
    </Drawer>
  </Page>
</template>

<style scoped>
.selected-row {
  background-color: rgb(22 119 255 / 8%);
}
</style>
