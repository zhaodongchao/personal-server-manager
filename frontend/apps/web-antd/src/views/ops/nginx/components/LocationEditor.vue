<script lang="ts" setup>
/**
 * 自定义 location 列表编辑器。
 *
 * <p>站点表单的内嵌子组件：支持增删多个 location 块，每个块按动作类型
 * （proxy / static / redirect / deny）切换对应参数输入，最终以 v-model 双向
 * 绑定 {@link NginxApi.NginxLocation[]} 交给站点表单。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { NginxApi } from '#/api';

import { ref, watch } from 'vue';

import { Button, Empty, Input, Select, Space } from 'ant-design-vue';

import { LOC_TYPE_OPTIONS } from '../utils';

defineOptions({ name: 'OpsNginxLocationEditor' });

const props = defineProps<{
  value?: NginxApi.NginxLocation[];
}>();

const emit = defineEmits<{
  'update:value': [NginxApi.NginxLocation[]];
}>();

function blank(): NginxApi.NginxLocation {
  return { path: '/', type: 'proxy', upstream: '', staticRoot: '', redirectTarget: '' };
}

const items = ref<NginxApi.NginxLocation[]>([]);

watch(
  () => props.value,
  (v) => {
    items.value = (v ?? []).map((it) => ({ ...blank(), ...it }));
  },
  { immediate: true },
);

function emitChange() {
  emit(
    'update:value',
    items.value.map((it) => ({ ...it })),
  );
}

function add() {
  items.value.push(blank());
  emitChange();
}

function remove(idx: number) {
  items.value.splice(idx, 1);
  emitChange();
}

function onFieldChange() {
  emitChange();
}
</script>

<template>
  <div class="space-y-2">
    <div
      v-for="(it, idx) in items"
      :key="idx"
      class="rounded border border-gray-200 p-2 dark:border-gray-700"
    >
      <div class="flex items-start gap-2">
        <Input
          v-model:value="it.path"
          class="w-40"
          placeholder="/api"
          @change="onFieldChange"
        >
          <template #addonBefore>路径</template>
        </Input>
        <Select
          v-model:value="it.type"
          :options="LOC_TYPE_OPTIONS"
          class="w-36"
          @change="onFieldChange"
        />
        <Button
          danger
          size="small"
          type="text"
          @click="remove(idx)"
        >
          删除
        </Button>
      </div>

      <div class="mt-2 pl-2">
        <Input
          v-if="it.type === 'proxy'"
          v-model:value="it.upstream"
          placeholder="上游地址，如 http://127.0.0.1:8080"
          @change="onFieldChange"
        >
          <template #addonBefore>上游</template>
        </Input>
        <Input
          v-else-if="it.type === 'static'"
          v-model:value="it.staticRoot"
          placeholder="静态根目录，如 /www/wwwroot/static"
          @change="onFieldChange"
        >
          <template #addonBefore>根目录</template>
        </Input>
        <Input
          v-else-if="it.type === 'redirect'"
          v-model:value="it.redirectTarget"
          placeholder="目标 URL，如 https://example.com"
          @change="onFieldChange"
        >
          <template #addonBefore>目标</template>
        </Input>
        <div
          v-else
          class="text-xs text-gray-400"
        >
          该 location 将返回 403，无需额外参数
        </div>
      </div>
    </div>

    <Empty
      v-if="items.length === 0"
      :image="Empty.PRESENTED_IMAGE_SIMPLE"
      description="暂无自定义 location"
    />

    <Space>
      <Button size="small" @click="add">添加 location</Button>
    </Space>
  </div>
</template>
