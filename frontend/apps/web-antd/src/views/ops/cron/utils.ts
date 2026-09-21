/**
 * 计划管理页通用工具：退出码语义、cron 常用模板、时间/耗时格式化。
 *
 * 退出码有 4 种语义（0 成功 / -1 异常 / -2 超时 / -3 并发跳过）。
 * 把它们收敛到一处，避免表格、抽屉、统计卡各写一套判断而出现色点不一致。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */

/** 退出码常量（与后端 CronJobService 保持一致） */
export const EXIT_OK = 0;
export const EXIT_ERROR = -1;
export const EXIT_TIMEOUT = -2;
export const EXIT_SKIPPED = -3;

export type ResultKey = 'success' | 'fail' | 'timeout' | 'skipped' | 'none';

/** 结果语义 → 展示元数据（颜色沿用 Ant Design 语义色） */
export const RESULT_META: Record<ResultKey, { label: string; color: string }> = {
  success: { label: '成功', color: 'green' },
  fail: { label: '失败', color: 'red' },
  timeout: { label: '超时', color: 'orange' },
  skipped: { label: '已跳过', color: 'default' },
  none: { label: '未执行', color: 'default' },
};

/** 退出码 → 结果语义 key */
export function resultKey(exitCode?: number | null): ResultKey {
  if (exitCode === null || exitCode === undefined) return 'none';
  if (exitCode === EXIT_OK) return 'success';
  if (exitCode === EXIT_TIMEOUT) return 'timeout';
  if (exitCode === EXIT_SKIPPED) return 'skipped';
  return 'fail';
}

/** 退出码 → 展示文案 */
export function resultLabel(exitCode?: number | null): string {
  return RESULT_META[resultKey(exitCode)].label;
}

/** 退出码 → 标签颜色 */
export function resultColor(exitCode?: number | null): string {
  return RESULT_META[resultKey(exitCode)].color;
}

/** 最近结果筛选下拉（与后端 applyJobResult 的取值一致） */
export const RESULT_OPTIONS = [
  { label: '成功', value: 'success' },
  { label: '失败', value: 'fail' },
  { label: '超时', value: 'timeout' },
  { label: '已跳过', value: 'skipped' },
];

/** 错过执行策略 */
export const MISFIRE_OPTIONS = [
  { label: '不补跑（推荐）', value: 'skip' },
  { label: '补跑一次', value: 'run_once' },
  { label: '全补（上限 10 次）', value: 'catch_up' },
];

/** 错过执行策略 → 展示文案 */
export function misfireLabel(value?: string): string {
  return (
    MISFIRE_OPTIONS.find((o) => o.value === (value ?? 'skip'))?.label ??
    '不补跑（推荐）'
  );
}

/** 并发策略 → 展示文案 */
export function overlapLabel(value?: string): string {
  return (
    OVERLAP_OPTIONS.find((o) => o.value === (value ?? 'skip'))?.label ??
    '已在运行则跳过（推荐）'
  );
}

/** 并发策略 */
export const OVERLAP_OPTIONS = [
  { label: '已在运行则跳过（推荐）', value: 'skip' },
  { label: '等待上一次结束（最多 60s）', value: 'queue' },
  { label: '允许并行', value: 'parallel' },
];

/**
 * cron 常用模板（5 段 unix 表达式）。
 *
 * 手写 cron 表达式极易出错（尤其是「日」与「周」同时指定时的语义），
 * 给一组常用模板能覆盖绝大多数运维场景，减少填错概率。
 */
export const CRON_TEMPLATES = [
  { label: '每分钟', value: '* * * * *' },
  { label: '每 5 分钟', value: '*/5 * * * *' },
  { label: '每 30 分钟', value: '*/30 * * * *' },
  { label: '每小时（整点）', value: '0 * * * *' },
  { label: '每天 02:00', value: '0 2 * * *' },
  { label: '每天 03:30', value: '30 3 * * *' },
  { label: '每周一 03:00', value: '0 3 * * 1' },
  { label: '每月 1 日 04:00', value: '0 4 1 * *' },
];

/** 触发方式 → 展示文案 */
export function triggerLabel(
  triggerType?: string,
  operator?: string,
): string {
  if (triggerType === 'manual') {
    return operator ? `手动 · ${operator}` : '手动';
  }
  if (triggerType === 'retry') {
    return '补跑';
  }
  return '定时';
}

/** 毫秒 → 人类可读耗时 */
export function fmtDuration(ms?: number | null): string {
  if (ms === null || ms === undefined || ms < 0) return '-';
  if (ms < 1000) return `${ms} ms`;
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) return `${totalSec} s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  if (min < 60) return sec ? `${min} 分 ${sec} 秒` : `${min} 分`;
  const hour = Math.floor(min / 60);
  return `${hour} 小时 ${min % 60} 分`;
}

/**
 * 距下次执行的倒计时文案。
 *
 * @param next 下次执行时间（后端 LocalDateTime 字符串）
 * @param now  当前时间戳。列表里传一个每 30s 递增的响应式值，
 *             让倒计时能被 Vue 追踪并自动刷新（否则渲染一次就再也不动）。
 */
export function fmtCountdown(next?: string | null, now: number = Date.now()): string {
  if (!next) return '-';
  const target = new Date(normalizeTime(next)).getTime();
  if (Number.isNaN(target)) return next;
  const diff = target - now;
  if (diff <= 0) return '即将执行';
  const totalSec = Math.floor(diff / 1000);
  const day = Math.floor(totalSec / 86_400);
  const hour = Math.floor((totalSec % 86_400) / 3600);
  const min = Math.floor((totalSec % 3600) / 60);
  const sec = totalSec % 60;
  if (day > 0) return `${day} 天 ${hour} 小时`;
  if (hour > 0) return `${hour} 小时 ${min} 分`;
  if (min > 0) return `${min} 分 ${sec} 秒`;
  return `${sec} 秒`;
}

/**
 * 后端返回的 LocalDateTime 形如 "2026-09-21T13:00:00"（无时区）。
 * 直接 new Date() 会被当成 UTC，与北京时间差 8 小时；这里补成本地时间语义。
 */
export function normalizeTime(text: string): string {
  if (!text) return text;
  return /[zZ]|[+-]\d{2}:?\d{2}$/.test(text) ? text : `${text.replace(' ', 'T')}`;
}

/** 时间格式化为 YYYY-MM-DD HH:mm:ss */
export function fmtTime(text?: string | null): string {
  if (!text) return '-';
  const d = new Date(normalizeTime(text));
  if (Number.isNaN(d.getTime())) return text;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(
    d.getHours(),
  )}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
