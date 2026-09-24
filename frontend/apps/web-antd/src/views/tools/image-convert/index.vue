<script setup lang="ts">
import type { ToolsApi } from '#/api/tools';

import { computed, onMounted, onUnmounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Checkbox,
  InputNumber,
  message,
  RadioButton,
  RadioGroup,
  Select,
  Slider,
  Spin,
  Upload,
} from 'ant-design-vue';

import { convertImagesApi, getImageConvertOptionsApi } from '#/api/tools';

defineOptions({ name: 'ToolsImageConvert' });

const { hasAccessByCodes } = useAccess();
const canConvert = computed(() => hasAccessByCodes(['tools:image:convert']));

// ===== options 数据 =====
const options = ref<ToolsApi.ImageConvertOptions>();
const formats = computed(() => options.value?.formats ?? []);
const limits = computed(() => options.value?.limits);
const optionsLoading = ref(false);

/**
 * 待转换文件条目：在原始 File 之上补充缩略图 URL 与探测出的像素尺寸，
 * 便于上传前直观确认「到底加了哪些图、各是什么规格」。
 */
interface PendingItem {
  file: File;
  height?: number;
  uid: string;
  url: string;
  width?: number;
}

// ===== 待转换文件 =====
const pendingItems = ref<PendingItem[]>([]);
let uidCounter = 0;

/** 已添加文件的总体积 */
const totalBytes = computed(() =>
  pendingItems.value.reduce((sum, item) => sum + item.file.size, 0),
);

// ===== 转换参数 =====
const targetFormat = ref<string>();
const resizeMode = ref<'dimension' | 'longEdge' | 'none' | 'percent'>('none');
const percent = ref(100);
const width = ref(800);
const height = ref(600);
const longEdge = ref(1280);
const keepRatio = ref(true);
const quality = ref(85);

// ===== 结果 =====
const converting = ref(false);
const results = ref<ToolsApi.ImageConvertResult[]>([]);

const targetMeta = computed(() =>
  formats.value.find((f) => f.format === targetFormat.value),
);
/** 当前目标格式是否支持质量参数 */
const qualityEnabled = computed(() => targetMeta.value?.qualitySupported ?? false);

const acceptTypes = '.jpg,.jpeg,.png,.webp,.bmp,.gif,.tif,.tiff';

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function sizeDelta(result: ToolsApi.ImageConvertResult) {
  if (result.sizeBefore === 0) return '';
  const pct = ((result.sizeAfter - result.sizeBefore) / result.sizeBefore) * 100;
  if (Math.abs(pct) < 0.5) return '体积基本持平';
  return pct < 0 ? `体积减小 ${Math.abs(pct).toFixed(0)}%` : `体积增大 ${pct.toFixed(0)}%`;
}

function limitText() {
  const l = limits.value;
  if (!l) return '';
  return `单个 ≤ ${formatSize(l.maxFileBytes)} · 最多 ${l.maxFiles} 张 · 总量 ≤ ${formatSize(l.maxTotalBytes)}`;
}

/** 从 MIME 或扩展名推断格式标签（如 PNG / JPEG），识别不出返回「未知」 */
function formatType(file: File) {
  const mime = file.type.split('/')[1]?.toUpperCase();
  if (mime && mime !== 'JPEG' && mime !== 'JPG') return mime;
  if (mime) return 'JPEG';
  const ext = file.name.split('.').pop()?.toUpperCase();
  if (!ext) return '未知';
  if (ext === 'JPG' || ext === 'JPEG') return 'JPEG';
  if (ext === 'TIF') return 'TIFF';
  return ext;
}

/** 异步探测图片像素尺寸（onload 后回写，卡片自动刷新） */
function probeSize(item: PendingItem) {
  const img = new Image();
  img.onload = () => {
    item.width = img.naturalWidth;
    item.height = img.naturalHeight;
  };
  img.src = item.url;
}

/** 手动收集文件（返回 false 阻止组件自动上传），同时做张数与单文件体积拦截 */
function onBeforeUpload(file: File) {
  const l = limits.value;
  if (l && pendingItems.value.length >= l.maxFiles) {
    message.warning(`单次最多 ${l.maxFiles} 个文件`);
    return false;
  }
  if (l && file.size > l.maxFileBytes) {
    message.warning(`「${file.name}」超过单文件上限 ${formatSize(l.maxFileBytes)}，已跳过`);
    return false;
  }
  const item: PendingItem = {
    file,
    uid: `img-${Date.now()}-${uidCounter++}`,
    url: URL.createObjectURL(file),
  };
  pendingItems.value.push(item);
  probeSize(item);
  return false;
}

function removeFile(index: number) {
  const [item] = pendingItems.value.splice(index, 1);
  if (item) {
    URL.revokeObjectURL(item.url);
  }
}

function clearFiles() {
  for (const item of pendingItems.value) {
    URL.revokeObjectURL(item.url);
  }
  pendingItems.value = [];
}

onUnmounted(() => {
  clearFiles();
});

async function fetchOptions() {
  optionsLoading.value = true;
  try {
    options.value = await getImageConvertOptionsApi();
    // 默认选中 JPEG（最常用目标格式）
    if (!targetFormat.value) {
      targetFormat.value = 'JPEG';
    }
  } finally {
    optionsLoading.value = false;
  }
}

function validateParams(): string | undefined {
  if (!targetFormat.value) return '请选择目标格式';
  if (pendingItems.value.length === 0) return '请先添加图片文件';
  const l = limits.value;
  if (resizeMode.value === 'percent' && l) {
    if (percent.value < l.minPercent || percent.value > l.maxPercent) {
      return `缩放百分比须在 ${l.minPercent}-${l.maxPercent} 之间`;
    }
  }
  if (resizeMode.value === 'dimension' && l) {
    if (
      width.value < l.minSide ||
      width.value > l.maxSide ||
      height.value < l.minSide ||
      height.value > l.maxSide
    ) {
      return `宽高须在 ${l.minSide}-${l.maxSide} 之间`;
    }
  }
  if (resizeMode.value === 'longEdge' && l) {
    if (longEdge.value < l.minSide || longEdge.value > l.maxSide) {
      return `最长边须在 ${l.minSide}-${l.maxSide} 之间`;
    }
  }
  if (qualityEnabled.value && l) {
    if (quality.value < l.minQuality || quality.value > l.maxQuality) {
      return `质量须在 ${l.minQuality}-${l.maxQuality} 之间`;
    }
  }
  return undefined;
}

async function handleConvert() {
  const err = validateParams();
  if (err) {
    message.warning(err);
    return;
  }
  converting.value = true;
  results.value = [];
  try {
    results.value = await convertImagesApi(
      pendingItems.value.map((item) => item.file),
      {
        targetFormat: targetFormat.value!,
        resizeMode: resizeMode.value,
        percent: resizeMode.value === 'percent' ? percent.value : undefined,
        width: resizeMode.value === 'dimension' ? width.value : undefined,
        height: resizeMode.value === 'dimension' ? height.value : undefined,
        longEdge: resizeMode.value === 'longEdge' ? longEdge.value : undefined,
        keepRatio: keepRatio.value,
        quality: qualityEnabled.value ? quality.value : undefined,
      },
    );
    const ok = results.value.filter((r) => r.success).length;
    const fail = results.value.length - ok;
    if (fail === 0) {
      message.success(`转换完成：${ok} 个文件`);
    } else {
      message.warning(`转换完成：成功 ${ok} 个，失败 ${fail} 个`);
    }
  } finally {
    converting.value = false;
  }
}

onMounted(() => {
  fetchOptions();
});
</script>

<template>
  <Page auto-content-height content-class="flex flex-col gap-4">
    <Alert
      type="info"
      show-icon
      message="支持 PNG / JPEG / WEBP / BMP / GIF / TIFF 六种主流格式互转，可设置尺寸缩放与输出质量；全程在服务器内存中完成，不保存任何图片"
    />

    <!-- 第一步：选择文件 -->
    <div class="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="text-base font-medium">1. 选择图片</h3>
        <span class="text-xs text-gray-400">{{ limitText() }}</span>
      </div>
      <Upload.Dragger
        accept="image/*"
        multiple
        :show-upload-list="false"
        :before-upload="onBeforeUpload"
        :disabled="!canConvert"
      >
        <p class="py-2 text-base">点击或拖拽图片到此处</p>
        <p class="text-xs text-gray-400">可多选，支持 {{ acceptTypes }}</p>
      </Upload.Dragger>
      <div v-if="pendingItems.length > 0" class="mt-3">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-xs text-gray-500">
            已添加 {{ pendingItems.length }} 张，共 {{ formatSize(totalBytes) }}
          </span>
          <Button size="small" danger type="text" @click="clearFiles">
            清空全部
          </Button>
        </div>
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
          <div
            v-for="(item, index) in pendingItems"
            :key="item.uid"
            class="relative rounded-lg border border-gray-200 p-2 transition hover:border-blue-400 dark:border-gray-600 dark:hover:border-blue-500"
          >
            <!-- 缩略图（棋盘格底，透明图可见） -->
            <div
              class="flex h-32 items-center justify-center rounded bg-[repeating-conic-gradient(#f0f0f0_0%_25%,white_0%_50%)] bg-[length:16px_16px] dark:bg-[repeating-conic-gradient(#374151_0%_25%,#1f2937_0%_50%)]"
            >
              <img
                :src="item.url"
                :alt="item.file.name"
                class="max-h-28 max-w-full object-contain"
              />
            </div>
            <!-- 文件信息 -->
            <div class="mt-2 space-y-0.5">
              <div
                class="truncate text-xs font-medium"
                :title="item.file.name"
              >
                {{ item.file.name }}
              </div>
              <div class="text-xs text-gray-400">
                {{ formatType(item.file) }} · {{ formatSize(item.file.size) }}
                <template v-if="item.width">
                  · {{ item.width }} × {{ item.height }} px
                </template>
                <template v-else>
                  · 尺寸识别中…
                </template>
              </div>
            </div>
            <!-- 移除按钮 -->
            <button
              type="button"
              title="移除该图片"
              class="absolute right-1 top-1 flex h-6 w-6 cursor-pointer items-center justify-center rounded-full bg-white/80 text-sm leading-none text-gray-500 shadow-sm transition hover:bg-red-500 hover:text-white dark:bg-gray-900/80"
              @click="removeFile(index)"
            >
              ×
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 第二步：转换参数 -->
    <div class="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900">
      <h3 class="mb-3 text-base font-medium">2. 转换参数</h3>
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <!-- 目标格式 -->
        <div>
          <div class="mb-1 text-sm">目标格式</div>
          <Select
            v-model:value="targetFormat"
            :options="formats.map((f) => ({ label: f.format, value: f.format }))"
            placeholder="选择目标格式"
            class="w-full"
          />
          <div v-if="targetMeta" class="mt-2 text-xs text-gray-400">
            {{ targetMeta.note }}
          </div>
        </div>

        <!-- 缩放方式 -->
        <div>
          <div class="mb-1 text-sm">尺寸缩放</div>
          <RadioGroup v-model:value="resizeMode" button-style="solid" class="mb-2">
            <RadioButton value="none">不缩放</RadioButton>
            <RadioButton value="percent">按百分比</RadioButton>
            <RadioButton value="dimension">指定宽高</RadioButton>
            <RadioButton value="longEdge">按最长边</RadioButton>
          </RadioGroup>
          <div v-if="resizeMode === 'percent'" class="flex items-center gap-2">
            <InputNumber v-model:value="percent" :min="1" :max="500" class="w-28" />
            <span class="text-sm text-gray-500">%</span>
          </div>
          <template v-if="resizeMode === 'dimension'">
            <div class="flex items-center gap-2">
              <InputNumber v-model:value="width" :min="1" :max="10000" class="w-28" addon-before="宽" />
              <InputNumber v-model:value="height" :min="1" :max="10000" class="w-28" addon-before="高" />
            </div>
            <Checkbox v-model:checked="keepRatio" class="mt-2 text-xs">
              保持纵横比（超出的边留白，取内切比例）
            </Checkbox>
          </template>
          <div v-if="resizeMode === 'longEdge'" class="flex items-center gap-2">
            <span class="text-sm text-gray-500">最长边</span>
            <InputNumber v-model:value="longEdge" :min="1" :max="10000" class="w-28" />
            <span class="text-xs text-gray-400">px</span>
          </div>
        </div>

        <!-- 输出质量 -->
        <div>
          <div class="mb-1 text-sm">
            输出质量
            <span v-if="targetMeta && !qualityEnabled" class="text-xs text-gray-400">
              （{{ targetMeta.format }} 为无损格式，该参数不生效）
            </span>
          </div>
          <Slider
            v-model:value="quality"
            :min="1"
            :max="100"
            :disabled="!qualityEnabled"
          />
          <div class="text-xs text-gray-400">
            {{ qualityEnabled ? `当前 ${quality}%，越低体积越小、清晰度越低` : '仅 JPEG / WEBP / TIFF 部分压缩支持' }}
          </div>
        </div>
      </div>

      <!-- 全局说明 -->
      <Alert v-if="options" type="warning" show-icon class="mt-4">
        <template #message>
          <ul class="list-disc pl-4 text-xs leading-5">
            <li v-for="note in options.notes" :key="note">{{ note }}</li>
          </ul>
        </template>
      </Alert>

      <div class="mt-4">
        <Spin :spinning="converting">
          <Button
            type="primary"
            :loading="converting"
            :disabled="!canConvert"
            @click="handleConvert"
          >
            开始转换
          </Button>
          <span v-if="!canConvert" class="ml-3 text-xs text-gray-400">
            需要 tools:image:convert 权限
          </span>
        </Spin>
      </div>
    </div>

    <!-- 第三步：转换结果 -->
    <div
      v-if="results.length > 0"
      class="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900"
    >
      <h3 class="mb-3 text-base font-medium">3. 转换结果</h3>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="(result, index) in results"
          :key="`${result.sourceName}-${index}`"
          class="rounded-lg border border-gray-200 p-3 dark:border-gray-600"
        >
          <template v-if="result.success">
            <div class="mb-2 truncate text-sm font-medium" :title="result.sourceName">
              {{ result.sourceName }}
            </div>
            <img
              :src="result.dataUrl"
              :alt="result.outputName"
              class="mb-2 max-h-48 w-full rounded bg-[repeating-conic-gradient(#f0f0f0_0%_25%,white_0%_50%)] bg-[length:16px_16px] object-contain"
            />
            <div class="space-y-1 text-xs text-gray-500">
              <div>
                {{ result.sourceFormat ?? '未知' }} → {{ result.targetFormat }}
                <span v-if="result.resized">
                  · 缩放至 {{ result.width }} × {{ result.height }}
                </span>
                <span v-else>· 保持原尺寸 {{ result.width }} × {{ result.height }}</span>
              </div>
              <div>
                {{ formatSize(result.sizeBefore) }} → {{ formatSize(result.sizeAfter) }}
                <span
                  :class="result.sizeAfter <= result.sizeBefore ? 'text-green-600' : 'text-orange-500'"
                >
                  {{ sizeDelta(result) }}
                </span>
              </div>
            </div>
            <Button type="primary" size="small" class="mt-2">
              <a :href="result.dataUrl" :download="result.outputName">下载 {{ result.outputName }}</a>
            </Button>
          </template>
          <template v-else>
            <div class="mb-2 truncate text-sm font-medium" :title="result.sourceName">
              {{ result.sourceName }}
            </div>
            <div class="rounded bg-red-50 p-2 text-xs text-red-600 dark:bg-red-900/30">
              转换失败：{{ result.error }}
            </div>
          </template>
        </div>
      </div>
    </div>
  </Page>
</template>
