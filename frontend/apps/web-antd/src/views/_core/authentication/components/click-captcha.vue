<script lang="ts" setup>
import type { AuthApi } from '#/api';

import { computed, ref, useTemplateRef, watch } from 'vue';

import { $t } from '@vben/locales';

/**
 * 点选人机校验：展示后端下发的字符图片，用户按提示顺序依次点击目标字符。
 *
 * 只负责「收集点击坐标」并把相对坐标（0~1，与显示尺寸无关）回传；
 * 图片、目标字符与正确答案全部来自后端，坐标校验也在后端完成 —— 本组件不参与判定，
 * 也不持有任何答案，避免把校验逻辑暴露在前端。
 *
 * 换图（含校验失败后自动刷新）会清空已点标记，防止新旧图坐标串味。
 */
const props = defineProps<{
  /** 后端下发的验证码图片（data URI） */
  image: string;
  /** 需按顺序点击的目标字符 */
  prompt: string[];
  /** 校验中 / 加载中：禁止继续点击 */
  disabled?: boolean;
}>();

const emit = defineEmits<{
  /** 已点满 prompt.length 个点，回传按顺序的相对坐标 */
  complete: [clicks: AuthApi.CaptchaClickPoint[]];
  /** 用户点击「换一张」 */
  refresh: [];
}>();

const stageRef = useTemplateRef<HTMLDivElement>('stageRef');

/** 已点击的点（相对坐标，按点击顺序） */
const points = ref<AuthApi.CaptchaClickPoint[]>([]);

const promptText = computed(() =>
  $t('authentication.captchaPrompt', [props.prompt.join(' ')]),
);

const filled = computed(() => points.value.length >= props.prompt.length);

watch(
  () => props.image,
  () => {
    points.value = [];
  },
);

function onClick(event: MouseEvent) {
  const stage = stageRef.value;
  if (!stage || props.disabled || filled.value) {
    return;
  }
  const rect = stage.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) {
    return;
  }
  // 归一化到 0~1：与图片实际显示尺寸无关，后端按原始宽高换算像素做命中判定
  const x = clamp01((event.clientX - rect.left) / rect.width);
  const y = clamp01((event.clientY - rect.top) / rect.height);
  points.value = [...points.value, { x, y }];
  if (points.value.length >= props.prompt.length) {
    emit('complete', points.value);
  }
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}

function markerStyle(point: AuthApi.CaptchaClickPoint) {
  return {
    left: `${point.x * 100}%`,
    top: `${point.y * 100}%`,
  };
}
</script>

<template>
  <div class="select-none">
    <p class="text-muted-foreground mb-2 text-sm">
      {{ promptText }}
    </p>

    <div
      ref="stageRef"
      class="relative overflow-hidden rounded-md border"
      :class="disabled || filled ? 'cursor-default' : 'cursor-crosshair'"
      @click="onClick"
    >
      <img :src="image" alt="" class="block w-full" draggable="false" />
      <!-- 已点标记：序号 + 定位点，pointer-events-none 不拦截后续点击 -->
      <span
        v-for="(point, index) in points"
        :key="index"
        class="bg-primary pointer-events-none absolute flex h-5 w-5 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full text-xs font-semibold text-white shadow"
        :style="markerStyle(point)"
      >
        {{ index + 1 }}
      </span>
    </div>

    <div class="mt-2 flex items-center justify-between">
      <span class="text-muted-foreground text-xs">
        {{ points.length }}/{{ prompt.length }}
      </span>
      <a
        class="text-primary text-xs hover:underline"
        :class="disabled ? 'pointer-events-none opacity-50' : ''"
        @click.prevent="emit('refresh')"
      >
        {{ $t('authentication.captchaRefresh') }}
      </a>
    </div>
  </div>
</template>
