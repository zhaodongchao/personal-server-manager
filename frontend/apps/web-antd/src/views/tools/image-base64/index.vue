<script lang="ts" setup>
import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  Button,
  Card,
  Input,
  Space,
  Switch,
  Tag,
  Upload,
  message,
} from 'ant-design-vue';

import { copyText } from '../copy';

defineOptions({ name: 'ToolsImageBase64' });

const TextArea = Input.TextArea;

/** 图片 → Base64 */
const dataUrl = ref('');
const keepPrefix = ref(true);
const fileMeta = ref<{ name: string; size: number; type: string } | null>(null);

/** Base64 → 图片 */
const base64Input = ref('');
const parseError = ref('');

const base64Text = computed(() => {
  if (!dataUrl.value) return '';
  return keepPrefix.value
    ? dataUrl.value
    : dataUrl.value.replace(/^data:[^;]*;base64,/, '');
});

/** 由 Base64 文本反推可用的 img src（自动补 data: 前缀） */
const previewSrc = computed(() => {
  const raw = base64Input.value.trim().replace(/\s+/g, '');
  if (!raw) return '';
  return raw.startsWith('data:') ? raw : `data:image/png;base64,${raw}`;
});

function beforeUpload(file: File) {
  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件');
    return false;
  }
  const reader = new FileReader();
  reader.onload = () => {
    dataUrl.value = String(reader.result || '');
    fileMeta.value = { name: file.name, size: file.size, type: file.type };
    // 顺手填到「Base64 → 图片」的输入框，方便来回验证
    base64Input.value = dataUrl.value;
    parseError.value = '';
  };
  reader.onerror = () => message.error('读取文件失败');
  reader.readAsDataURL(file);
  return false; // 阻止 antd 自动上传（本工具不走网络）
}

function onBase64Input() {
  parseError.value = '';
  const raw = base64Input.value.trim().replace(/\s+/g, '');
  if (!raw) return;
  const body = raw.startsWith('data:') ? raw.slice(raw.indexOf(',') + 1) : raw;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(body)) {
    parseError.value = '不是合法的 Base64 字符串';
  }
}

function download() {
  const src = previewSrc.value;
  if (!src) {
    message.warning('没有可下载的图片');
    return;
  }
  const a = document.createElement('a');
  a.href = src;
  a.download = `image-${Date.now()}.png`;
  a.click();
}

function toImage() {
  dataUrl.value = previewSrc.value;
}

function clearAll() {
  dataUrl.value = '';
  base64Input.value = '';
  fileMeta.value = null;
  parseError.value = '';
}
</script>

<template>
  <Page
    title="图片与 Base64 互转"
    description="浏览器本地完成，图片不会上传到服务器 — 支持粘贴 Base64 反解预览与下载"
  >
    <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <!-- 图片 → Base64 -->
      <Card title="图片 → Base64">
        <Upload
          :before-upload="beforeUpload"
          :max-count="1"
          :show-upload-list="false"
          accept="image/*"
        >
          <Button type="primary">选择图片</Button>
        </Upload>

        <div v-if="fileMeta" class="mt-3 text-sm text-gray-500">
          {{ fileMeta.name }} · {{ fileMeta.type }} ·
          {{ (fileMeta.size / 1024).toFixed(1) }} KB
          <Tag class="ml-2" color="blue">
            Base64 后约 {{ Math.ceil((fileMeta.size * 4) / 3 / 1024) }} KB
          </Tag>
        </div>

        <div v-if="previewSrc" class="mt-3">
          <img
            :src="dataUrl || previewSrc"
            alt="预览"
            class="max-h-52 rounded border"
          />
        </div>

        <div class="mt-3 flex items-center gap-2">
          <span class="text-sm text-gray-500">保留 data: 前缀</span>
          <Switch v-model:checked="keepPrefix" />
        </div>

        <TextArea :value="base64Text" :rows="6" class="mt-3" readonly />
        <div class="mt-3">
          <Space>
            <Button :disabled="!base64Text" @click="copyText(base64Text)">
              复制 Base64
            </Button>
            <Button @click="clearAll">清空</Button>
          </Space>
        </div>
      </Card>

      <!-- Base64 → 图片 -->
      <Card title="Base64 → 图片">
        <div class="mb-2 text-sm text-gray-500">
          粘贴 Base64（可带 data: 前缀，也可是裸 Base64）
        </div>
        <TextArea
          v-model:value="base64Input"
          :rows="6"
          allow-clear
          placeholder="data:image/png;base64,iVBORw0KGgo..."
          @change="onBase64Input"
        />
        <div v-if="parseError" class="mt-2 text-sm text-red-500">
          {{ parseError }}
        </div>

        <div class="mt-3">
          <Space>
            <Button :disabled="!previewSrc" type="primary" @click="toImage">
              生成预览
            </Button>
            <Button :disabled="!previewSrc" @click="download">下载图片</Button>
            <Button :disabled="!base64Input" @click="copyText(base64Input)">
              复制输入
            </Button>
          </Space>
        </div>

        <div v-if="previewSrc" class="mt-3">
          <img :src="previewSrc" alt="反解预览" class="max-h-52 rounded border" />
        </div>
      </Card>
    </div>
  </Page>
</template>
