<script lang="ts" setup>
import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { Button, Card, Input, Radio, Space, Switch, Tag } from 'ant-design-vue';

import { copyText } from '../copy';

defineOptions({ name: 'ToolsUrlCodec' });

const TextArea = Input.TextArea;

const input = ref('');
/** component = 按参数值编码（更彻底）；whole = 按整个 URL 编码（保留 : / ? # 等分隔符） */
const scope = ref<'component' | 'whole'>('component');
const op = ref<'DECODE' | 'ENCODE'>('ENCODE');
const batch = ref(false);

/** 纯前端计算：无需后端，也不存在把明文送到服务端的审计面 */
const output = computed(() => {
  const src = input.value;
  if (!src) return '';
  const lines = batch.value ? src.split('\n') : [src];
  return lines
    .map((line) => {
      if (!line) return '';
      try {
        if (op.value === 'ENCODE') {
          return scope.value === 'component'
            ? encodeURIComponent(line)
            : encodeURI(line);
        }
        return scope.value === 'component'
          ? decodeURIComponent(line)
          : decodeURI(line);
      } catch {
        return '⚠ 解码失败：不是合法的百分号编码';
      }
    })
    .join('\n');
});

function swap() {
  const next = output.value;
  input.value = next;
  op.value = op.value === 'ENCODE' ? 'DECODE' : 'ENCODE';
}
</script>

<template>
  <Page
    title="URL 加密解密"
    description="百分号编码（URL Encoding）与解码 — 纯浏览器本地计算，不经过服务端"
  >
    <Card>
      <div class="flex flex-wrap items-center gap-4">
        <Radio.Group v-model:value="op">
          <Radio.Button value="ENCODE">编码</Radio.Button>
          <Radio.Button value="DECODE">解码</Radio.Button>
        </Radio.Group>
        <Radio.Group v-model:value="scope">
          <Radio.Button value="component">按参数值</Radio.Button>
          <Radio.Button value="whole">按整个 URL</Radio.Button>
        </Radio.Group>
        <span class="text-sm text-gray-500">逐行批量</span>
        <Switch v-model:checked="batch" />
        <Tag v-if="scope === 'component'" color="blue">
          encodeURIComponent：除 -_.!~*'() 与字母数字外全部转义
        </Tag>
        <Tag v-else color="green">
          encodeURI：保留 : / ? # [ ] @ 等 URL 分隔符，只转义非法字符
        </Tag>
      </div>

      <div class="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
        <div>
          <div class="mb-2 text-sm text-gray-500">输入</div>
          <TextArea
            v-model:value="input"
            :rows="10"
            allow-clear
            placeholder="https://example.com/api?q=中文 空格&next=/a/b"
          />
        </div>
        <div>
          <div class="mb-2 text-sm text-gray-500">输出（实时）</div>
          <TextArea :value="output" :rows="10" readonly />
        </div>
      </div>

      <div class="mt-3">
        <Space>
          <Button :disabled="!output" @click="copyText(output)">复制结果</Button>
          <Button :disabled="!output" @click="swap">结果回填到输入并反向</Button>
        </Space>
      </div>
    </Card>
  </Page>
</template>
