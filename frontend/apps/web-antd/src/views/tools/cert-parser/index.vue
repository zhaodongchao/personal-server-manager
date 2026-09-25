<script lang="ts" setup>
import type { CertApi } from '#/api';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Collapse,
  CollapsePanel,
  Input,
  message,
  TabPane,
  Tabs,
  Tag,
} from 'ant-design-vue';

import { getCertOptionsApi, parseCertApi } from '#/api';

defineOptions({ name: 'ToolsCertParser' });

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const options = ref<CertApi.CertOptions>();
const activeTab = ref('idCard');
const value = ref('');
const result = ref<CertApi.CertParseResult>();

const levelColor: Record<string, string> = {
  ok: 'green',
  warn: 'orange',
  error: 'red',
};
const levelText: Record<string, string> = {
  ok: '解析通过',
  warn: '通过（有提示）',
  error: '解析不通过',
};

async function loadOptions() {
  options.value = await getCertOptionsApi();
}

function onTabChange(key: number | string) {
  activeTab.value = String(key);
  value.value = '';
  result.value = undefined;
}

async function parse() {
  if (!value.value.trim()) {
    message.warning('请输入证件号码');
    return;
  }
  loading.value = true;
  result.value = undefined;
  try {
    result.value = await parseCertApi({ type: activeTab.value, value: value.value });
  } finally {
    loading.value = false;
  }
}

onMounted(loadOptions);
</script>

<template>
  <Page title="证件解析" description="身份证 / 手机号 / 银行卡 / 统一社会信用代码 / 车牌号 / 回乡证 / 台胞证">
    <Alert
      class="mb-4"
      show-icon
      type="info"
      message="隐私声明：解析在服务器内存中完成，不落库、不记录证件号原文；审计日志只留操作人与结果级别。"
    />
    <Alert
      v-if="options && !options.limits.regionReady"
      class="mb-4"
      show-icon
      type="warning"
      message="行政区划数据尚未同步：身份证/信用代码的区划解析已降级，可在「系统管理-基础数据」中同步。"
    />

    <Tabs v-model:activeKey="activeTab" @change="onTabChange">
      <TabPane v-for="t in options?.types" :key="t.key" :tab="t.name">
        <div class="mb-4 flex flex-wrap items-center gap-2">
          <Input
            v-model:value="value"
            class="max-w-[520px] flex-1"
            :placeholder="t.placeholder"
            :maxlength="options?.limits.maxValueLength ?? 64"
            allow-clear
            @pressEnter="parse"
          />
          <Button
            v-if="hasAccessByCodes(['tools:cert:exec'])"
            :loading="loading"
            type="primary"
            @click="parse"
          >
            解析
          </Button>
        </div>
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <span class="text-xs opacity-60">示例：</span>
          <Tag
            v-for="sample in t.samples"
            :key="sample"
            class="cursor-pointer"
            color="blue"
            @click="value = sample"
          >
            {{ sample }}
          </Tag>
        </div>

        <!-- 结果卡 -->
        <div
          v-if="result"
          class="mb-4 rounded-lg border p-4"
          :class="{
            'border-green-300 bg-green-50/60 dark:border-green-700 dark:bg-green-900/10': result.level === 'ok',
            'border-orange-300 bg-orange-50/60 dark:border-orange-700 dark:bg-orange-900/10': result.level === 'warn',
            'border-red-300 bg-red-50/60 dark:border-red-700 dark:bg-red-900/10': result.level === 'error',
          }"
        >
          <div class="mb-3 flex items-center gap-2">
            <Tag :color="levelColor[result.level]">{{ levelText[result.level] }}</Tag>
          </div>
          <Alert
            v-for="(err, i) in result.errors"
            :key="`e${i}`"
            class="mb-2"
            type="error"
            show-icon
            :message="err"
          />
          <Alert
            v-for="(warn, i) in result.warns"
            :key="`w${i}`"
            class="mb-2"
            type="warning"
            show-icon
            :message="warn"
          />
          <table v-if="result.fields.length" class="w-full max-w-[640px] text-sm">
            <tbody>
              <tr
                v-for="field in result.fields"
                :key="field.label"
                class="border-b border-gray-200 last:border-b-0 dark:border-gray-700"
              >
                <td class="w-36 py-1.5 align-top opacity-60">{{ field.label }}</td>
                <td class="py-1.5 font-medium">{{ field.value }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 构造规则 -->
        <Collapse>
          <CollapsePanel key="rules" header="构造规则">
            <div v-for="section in t.rules" :key="section.title" class="mb-3 last:mb-0">
              <div class="mb-1 font-medium">{{ section.title }}</div>
              <div
                v-for="item in section.items"
                :key="item.label"
                class="mb-1.5 flex flex-col gap-0.5 text-sm sm:flex-row"
              >
                <span class="w-52 shrink-0 opacity-70">{{ item.label }}</span>
                <span>{{ item.desc }}</span>
              </div>
            </div>
          </CollapsePanel>
        </Collapse>
      </TabPane>
    </Tabs>
  </Page>
</template>
