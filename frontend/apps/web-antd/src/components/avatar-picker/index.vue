<script lang="ts" setup>
/**
 * 头像选择器：内置预设头像 + 手动上传（前端压缩为 256×256 JPEG 后转 base64）。
 *
 * 值语义与后端三态一一对应，可直接透传：
 *   null                          → 未设置（提交时表示「本次不修改」）
 *   ''                            → 显式恢复系统默认头像 /avatar.svg（提交时表示「清除」）
 *   preset:N                      → 内置预设头像 public/avatars/preset-N.svg
 *   data:image/jpeg;base64,...    → 自定义上传头像
 *
 * 组件适配项目表单约定（adapter/form.ts 的 baseModelPropName = 'value'），
 * 因此对外暴露 `value` / `update:value`，可在 useVbenForm 的 schema 中直接使用。
 */
import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, message, Popover, Upload } from 'ant-design-vue';

defineOptions({ name: 'AvatarPicker' });

interface Props {
  /** 是否禁用交互 */
  disabled?: boolean;
  /** 内置预设头像数量，与 public/avatars/preset-N.svg 一一对应 */
  presetCount?: number;
  /** 紧凑模式：只展示头像，操作收进气泡面板（用于个人中心顶部头像） */
  compact?: boolean;
  /** 当前头像值：null / preset:N / data:image/...;base64,... */
  value?: null | string;
}

const props = withDefaults(defineProps<Props>(), {
  compact: false,
  disabled: false,
  presetCount: 8,
  value: null,
});

const emit = defineEmits<{
  'update:value': [null | string];
}>();

/** 系统默认头像（public 下的站内静态资源） */
const DEFAULT_AVATAR = '/avatar.svg';
/** 预设头像静态资源前缀 */
const PRESET_URL_PREFIX = '/avatars/preset-';
/** 上传前的原始图片体积上限 */
const MAX_RAW_BYTES = 5 * 1024 * 1024;
/** 压缩后的最长边 */
const MAX_SIDE = 256;
/** JPEG 压缩质量 */
const JPEG_QUALITY = 0.85;

/** 处理中标记（压缩耗时，避免重复点击） */
const processing = ref(false);

/** 当前头像的可渲染 src；由当前值直接推导，无需外部传入 */
const previewSrc = computed(() => {
  const current = props.value;
  if (!current) {
    return DEFAULT_AVATAR;
  }
  if (current.startsWith('preset:')) {
    return `${PRESET_URL_PREFIX}${current.slice('preset:'.length)}.svg`;
  }
  return current;
});

/** 预设头像列表 */
const presets = computed(() =>
  Array.from({ length: props.presetCount }, (_, index) => {
    const no = index + 1;
    return { key: `preset:${no}`, src: `${PRESET_URL_PREFIX}${no}.svg` };
  }),
);

/** 是否已处于「系统默认」状态（未设置或已显式恢复默认） */
const isDefault = computed(() => !props.value);

function selectPreset(key: string) {
  if (props.disabled) {
    return;
  }
  emit('update:value', key);
}

/**
 * 恢复系统默认头像。
 *
 * 这里刻意发**空串**而不是 null：null 在提交协议里表示「本次不修改头像」，
 * 只有空串才会被后端解析为「清除头像，恢复默认」。组件内部则把二者都视为默认态。
 */
function resetToDefault() {
  if (props.disabled) {
    return;
  }
  emit('update:value', '');
}

/** 拦截 antd Upload 的自动上传，改为本地压缩处理 */
function beforeUpload(file: File) {
  void handleFile(file);
  return false;
}

async function handleFile(file: File) {
  if (props.disabled) {
    return;
  }
  if (!/^image\/(jpeg|png|webp)$/.test(file.type)) {
    message.error('仅支持 PNG / JPEG / WebP 图片');
    return;
  }
  if (file.size > MAX_RAW_BYTES) {
    message.error('图片不能超过 5MB，请先压缩');
    return;
  }
  processing.value = true;
  try {
    const dataUrl = await compressToDataUrl(file);
    emit('update:value', dataUrl);
    message.success('已选择新头像，保存后生效');
  } catch {
    message.error('图片处理失败，请换一张试试');
  } finally {
    processing.value = false;
  }
}

/**
 * 等比缩放到最长边 256px 并转为 JPEG base64 data URL。
 * 典型产物 10~25KB，远低于后端 1MB 校验上限。
 */
function compressToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      try {
        const scale = Math.min(1, MAX_SIDE / Math.max(image.width, image.height));
        const width = Math.max(1, Math.round(image.width * scale));
        const height = Math.max(1, Math.round(image.height * scale));
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext('2d');
        if (!context) {
          throw new Error('canvas 2d context unavailable');
        }
        // 铺白底：避免 PNG 透明区域在 JPEG 下变黑
        context.fillStyle = '#ffffff';
        context.fillRect(0, 0, width, height);
        context.drawImage(image, 0, 0, width, height);
        resolve(canvas.toDataURL('image/jpeg', JPEG_QUALITY));
      } catch (error) {
        reject(error);
      } finally {
        URL.revokeObjectURL(objectUrl);
      }
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('image decode failed'));
    };
    image.src = objectUrl;
  });
}
</script>

<template>
  <!-- 紧凑模式：头像 + 悬浮提示，点击弹出操作面板 -->
  <Popover v-if="compact" placement="bottomLeft" trigger="click">
    <template #content>
      <div class="w-[268px]">
        <div class="mb-2 text-xs text-gray-500">选择默认头像</div>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="item in presets"
            :key="item.key"
            :class="[
              'size-11 overflow-hidden rounded-full border-2 transition',
              value === item.key
                ? 'border-primary'
                : 'border-transparent hover:border-gray-300',
            ]"
            :disabled="disabled"
            type="button"
            @click="selectPreset(item.key)"
          >
            <img :alt="item.key" class="size-full" :src="item.src" />
          </button>
        </div>
        <div class="mt-3 flex items-center gap-2">
          <Upload
            accept="image/png,image/jpeg,image/webp"
            :before-upload="beforeUpload"
            :disabled="disabled || processing"
            :show-upload-list="false"
          >
            <Button :loading="processing" size="small">上传图片</Button>
          </Upload>
          <Button :disabled="disabled || isDefault" size="small" @click="resetToDefault">
            恢复默认
          </Button>
        </div>
        <p class="mt-2 text-xs leading-4 text-gray-400">
          支持 PNG / JPEG / WebP，自动压缩为 256×256
        </p>
      </div>
    </template>
    <div
      :class="[
        'group relative size-20 cursor-pointer overflow-hidden rounded-full',
        disabled ? 'cursor-not-allowed opacity-60' : '',
      ]"
    >
      <img alt="头像" class="size-full object-cover" :src="previewSrc" />
      <div
        class="absolute inset-0 flex-col-center gap-1 bg-black/45 text-white opacity-0 transition group-hover:opacity-100"
      >
        <IconifyIcon class="size-5" icon="lucide:camera" />
        <span class="text-xs">更换</span>
      </div>
    </div>
  </Popover>

  <!-- 标准模式：头像预览 + 操作按钮 + 预设网格 -->
  <div v-else class="w-full">
    <div class="flex items-start gap-4">
      <div
        class="size-[88px] flex-none overflow-hidden rounded-full border border-gray-200 bg-gray-50"
      >
        <img alt="头像预览" class="size-full object-cover" :src="previewSrc" />
      </div>
      <div class="flex min-w-0 flex-col gap-2">
        <div class="flex flex-wrap items-center gap-2">
          <Upload
            accept="image/png,image/jpeg,image/webp"
            :before-upload="beforeUpload"
            :disabled="disabled || processing"
            :show-upload-list="false"
          >
            <Button :loading="processing" size="small">
              <IconifyIcon class="mr-1 size-3.5" icon="lucide:image-up" />
              上传图片
            </Button>
          </Upload>
          <Button :disabled="disabled || isDefault" size="small" @click="resetToDefault">
            恢复默认
          </Button>
        </div>
        <p class="text-xs leading-4 text-gray-400">
          支持 PNG / JPEG / WebP，自动压缩为 256×256，单张不超过 1MB
        </p>
      </div>
    </div>

    <div class="mt-3">
      <div class="mb-2 text-xs text-gray-500">默认头像</div>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="item in presets"
          :key="item.key"
          :class="[
            'size-11 overflow-hidden rounded-lg border-2 transition',
            value === item.key
              ? 'border-primary'
              : 'border-transparent hover:border-gray-300',
          ]"
          :disabled="disabled"
          type="button"
          @click="selectPreset(item.key)"
        >
          <img :alt="item.key" class="size-full" :src="item.src" />
        </button>
      </div>
    </div>
  </div>
</template>
