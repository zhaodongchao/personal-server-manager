<script lang="ts" setup>
/**
 * 计划任务详情抽屉
 *
 * 回答三个「列表里看不出来」的问题：
 * 1. 这个表达式到底什么时候跑？（人话描述 + 未来 5 次具体时间）
 * 2. 最近跑得怎么样？（最近 20 次的结果色条 + 耗时，一眼看出是否稳定劣化）
 * 3. 连续失败到什么程度会被自动停用？（failCount / maxFail）
 *
 * 趋势刻意用纯 div 色条渲染，不引入 echarts：这里只需要「一眼看出成色」，
 * 图表库的体积与初始化成本不值得。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Descriptions,
  Drawer,
  Empty,
  Skeleton,
  Space,
  Tag,
  TypographyText,
} from 'ant-design-vue';

import { getCronLogPageApi, previewCronExprApi } from '#/api';

import {
  fmtDuration,
  fmtTime,
  misfireLabel,
  overlapLabel,
  resultColor,
  resultLabel,
} from '../utils';

defineOptions({ name: 'OpsCronDetailDrawer' });

const props = defineProps<{
  open: boolean;
  job: OpsApi.CronJob | null;
}>();

const emit = defineEmits<{
  'update:open': [boolean];
}>();

const loading = ref(false);
const preview = ref<OpsApi.CronPreview>();
const recent = ref<OpsApi.CronLog[]>([]);

watch(
  () => [props.open, props.job?.id] as const,
  ([open]) => {
    if (!open || !props.job?.id) return;
    void load();
  },
);

async function load() {
  const job = props.job;
  if (!job?.id) return;
  loading.value = true;
  try {
    const [p, logs] = await Promise.all([
      previewCronExprApi({ cronExpr: job.cronExpr, count: 5 }).catch(
        () => undefined,
      ),
      getCronLogPageApi(job.id, { pageNum: 1, pageSize: 20 }).catch(() => ({
        records: [] as OpsApi.CronLog[],
      })),
    ]);
    preview.value = p;
    recent.value = (logs as { records?: OpsApi.CronLog[] })?.records ?? [];
  } finally {
    loading.value = false;
  }
}

const failRate = computed(() => {
  const list = recent.value;
  if (!list.length) return 0;
  const bad = list.filter((r) => r.exitCode !== 0).length;
  return Math.round((bad / list.length) * 100);
});

/** 耗时条的相对高度（以最近 20 次的最大耗时为基准） */
const maxDuration = computed(() =>
  Math.max(1, ...recent.value.map((r) => r.durationMs ?? 0)),
);

function barHeight(ms?: number) {
  const v = ms ?? 0;
  return `${Math.max(8, Math.round((v / maxDuration.value) * 100))}%`;
}
</script>

<template>
  <Drawer
    :open="props.open"
    :width="720"
    :title="`任务详情 · ${props.job?.name ?? ''}`"
    @close="emit('update:open', false)"
  >
    <Skeleton v-if="loading" active :paragraph="{ rows: 6 }" />
    <template v-else-if="props.job">
      <Descriptions bordered :column="2" size="small">
        <Descriptions.Item label="任务名">{{ props.job.name }}</Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag :color="props.job.status === 1 ? 'green' : 'default'">
            {{ props.job.status === 1 ? '启用' : '停用' }}
          </Tag>
          <Tag v-if="props.job.running" color="processing">执行中</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="cron 表达式">
          <code>{{ props.job.cronExpr }}</code>
        </Descriptions.Item>
        <Descriptions.Item label="人话">
          {{ props.job.humanExpr || preview?.humanExpr || '-' }}
        </Descriptions.Item>
        <Descriptions.Item label="命令" :span="2">
          <code class="break-all">{{ props.job.command }}</code>
        </Descriptions.Item>
        <Descriptions.Item label="超时">
          {{ props.job.timeoutSec }} 秒
        </Descriptions.Item>
        <Descriptions.Item label="上次执行">
          {{ fmtTime(props.job.lastRunAt) }}
          <Tag
            v-if="props.job.lastExitCode !== undefined"
            :color="resultColor(props.job.lastExitCode)"
            class="ml-1"
          >
            {{ resultLabel(props.job.lastExitCode) }}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="下次执行">
          {{ fmtTime(props.job.nextRunAt) }}
        </Descriptions.Item>
        <Descriptions.Item label="错过执行策略">
          {{ misfireLabel(props.job.misfirePolicy) }}
        </Descriptions.Item>
        <Descriptions.Item label="并发策略">
          {{ overlapLabel(props.job.overlapPolicy) }}
        </Descriptions.Item>
        <Descriptions.Item label="连续失败">
          {{ props.job.failCount ?? 0 }}
          /
          {{ props.job.maxFail && props.job.maxFail > 0 ? props.job.maxFail : '不停用' }}
        </Descriptions.Item>
        <Descriptions.Item v-if="props.job.remark" label="备注" :span="2">
          {{ props.job.remark }}
        </Descriptions.Item>
      </Descriptions>

      <div class="mt-4">
        <TypographyText strong>未来 5 次执行</TypographyText>
        <div class="mt-2 flex flex-wrap gap-1">
          <Tag
            v-for="t in preview?.nextTimes ?? []"
            :key="t"
            color="blue"
            class="m-0"
          >
            {{ fmtTime(t) }}
          </Tag>
          <TypographyText
            v-if="preview && !preview.valid"
            type="danger"
          >
            表达式无效：{{ preview.message }}
          </TypographyText>
          <TypographyText v-else-if="!preview?.nextTimes?.length" type="secondary">
            该表达式在未来不再触发
          </TypographyText>
        </div>
      </div>

      <div class="mt-4">
        <Space :size="8">
          <TypographyText strong>最近 20 次执行</TypographyText>
          <TypographyText v-if="recent.length" type="secondary">
            非成功占比 {{ failRate }}%
          </TypographyText>
        </Space>
        <Empty
          v-if="!recent.length"
          description="暂无执行记录"
          class="mt-3"
          :image="undefined"
        />
        <div v-else class="mt-3">
          <div class="flex h-24 items-end gap-1">
            <div
              v-for="row in recent"
              :key="row.id"
              class="w-3 rounded-sm"
              :style="{
                height: barHeight(row.durationMs),
                backgroundColor:
                  resultColor(row.exitCode) === 'green'
                    ? '#52c41a'
                    : resultColor(row.exitCode) === 'orange'
                      ? '#fa8c16'
                      : resultColor(row.exitCode) === 'red'
                        ? '#ff4d4f'
                        : '#bfbfbf',
              }"
              :title="`${fmtTime(row.startedAt)} · ${resultLabel(
                row.exitCode,
              )} · ${fmtDuration(row.durationMs)}`"
            />
          </div>
          <TypographyText type="secondary" class="text-xs">
            左=最早，右=最近；柱高表示相对耗时，颜色表示结果
          </TypographyText>
        </div>
      </div>

      <Alert
        v-if="(props.job.failCount ?? 0) > 0 && (props.job.maxFail ?? 0) > 0"
        class="mt-4"
        type="warning"
        show-icon
        :message="`已连续失败 ${props.job.failCount} 次，达到 ${props.job.maxFail} 次将自动停用`"
      />
    </template>
  </Drawer>
</template>
