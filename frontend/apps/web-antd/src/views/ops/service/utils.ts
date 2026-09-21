/**
 * 服务管理页共用格式化与判定工具。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */

/** 字节 -> 人类可读 */
export function fmtBytes(value?: null | number): string {
  if (value === undefined || value === null || value <= 0) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let n = value;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  const digits = i === 0 ? 0 : n >= 100 ? 0 : n >= 10 ? 1 : 2;
  return `${n.toFixed(digits)} ${units[i]}`;
}

/** 纳秒 -> 人类可读 CPU 时间 */
export function fmtCpuNanos(value?: null | number): string {
  if (value === undefined || value === null || value <= 0) {
    return '-';
  }
  return `${(value / 1_000_000_000).toFixed(2)} s`;
}

/** 秒 -> 中文时长 */
export function fmtDuration(seconds?: null | number): string {
  if (seconds === undefined || seconds === null || seconds < 0) {
    return '-';
  }
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (d > 0) {
    return `${d} 天 ${h} 小时`;
  }
  if (h > 0) {
    return `${h} 小时 ${m} 分`;
  }
  if (m > 0) {
    return `${m} 分 ${s} 秒`;
  }
  return `${s} 秒`;
}

/** epoch 毫秒 -> 本地时间串 */
export function fmtEpochMs(ms?: null | number): string {
  if (ms === undefined || ms === null || ms <= 0) {
    return '-';
  }
  return new Date(ms).toLocaleString('zh-CN', { hour12: false });
}

/** 运行状态 -> Tag 颜色 */
export function activeColor(active?: string): string {
  switch (active) {
    case 'active': {
      return 'success';
    }
    case 'activating':
    case 'reloading': {
      return 'processing';
    }
    case 'failed': {
      return 'error';
    }
    case 'deactivating': {
      return 'warning';
    }
    default: {
      return 'default';
    }
  }
}

/** 运行状态 -> 中文 */
export function activeLabel(active?: string): string {
  const map: Record<string, string> = {
    active: '运行中',
    activating: '启动中',
    deactivating: '停止中',
    failed: '失败',
    inactive: '已停止',
    reloading: '重载中',
  };
  return map[active ?? ''] ?? (active || '-');
}

/** 子状态 -> 中文 */
export function subLabel(sub?: string): string {
  const map: Record<string, string> = {
    dead: '已退出',
    exited: '已退出',
    failed: '失败',
    running: '运行中',
    start: '启动中',
    stop: '停止中',
    waiting: '等待中',
  };
  return map[sub ?? ''] ?? (sub || '-');
}

/** 开机自启状态 -> Tag 颜色 */
export function unitFileStateColor(state?: string): string {
  switch (state) {
    case 'enabled':
    case 'enabled-runtime': {
      return 'blue';
    }
    case 'masked':
    case 'masked-runtime': {
      return 'red';
    }
    case 'static':
    case 'indirect':
    case 'alias': {
      return 'purple';
    }
    default: {
      return 'default';
    }
  }
}

/** 开机自启状态 -> 中文 */
export function unitFileStateLabel(state?: string): string {
  const map: Record<string, string> = {
    alias: '别名',
    disabled: '已禁用',
    enabled: '已启用',
    'enabled-runtime': '已启用(临时)',
    generated: '自动生成',
    indirect: '间接',
    masked: '已屏蔽',
    'masked-runtime': '已屏蔽(临时)',
    static: '静态',
  };
  return map[state ?? ''] ?? (state || '-');
}

/** 低危动作（即便命中保护清单也不升 L3），与后端 SAFE_ACTIONS 保持一致 */
export const SAFE_ACTIONS = new Set([
  'start',
  'reload',
  'try-restart',
  'reset-failed',
]);

/** 危险动作判定，与后端 isDangerAction 保持一致 */
export function isDangerAction(action: string, protectedUnit: boolean): boolean {
  if (action === 'mask' || action === 'unmask') {
    return true;
  }
  return protectedUnit && !SAFE_ACTIONS.has(action);
}

/** 行内常用动作 */
export const ROW_ACTIONS = [
  { key: 'start', label: '启动' },
  { key: 'stop', label: '停止' },
  { key: 'restart', label: '重启' },
  { key: 'reload', label: '重载' },
] as const;

/** 更多动作（下拉） */
export const MORE_ACTIONS = [
  { key: 'try-restart', label: '仅在运行中时重启' },
  { key: 'reset-failed', label: '清除失败状态' },
  { type: 'divider' as const },
  { key: 'enable', label: '设为开机自启' },
  { key: 'disable', label: '取消开机自启' },
  { key: 'enable:now', label: '设为自启并立即启动' },
  { key: 'disable:now', label: '取消自启并立即停止' },
  { type: 'divider' as const },
  { key: 'mask', label: '屏蔽（禁止启动）', danger: true },
  { key: 'unmask', label: '解除屏蔽', danger: true },
  { key: 'kill', label: '结束主进程 (SIGTERM)', danger: true },
] as const;
