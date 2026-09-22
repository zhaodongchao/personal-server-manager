/**
 * 服务器配置管理页面的展示层工具。
 *
 * <p>只做「后端字段 → 页面文案/颜色」的映射与只读格式化，不含任何业务判定：
 * 是否允许生效、是否需要键入关键字，一律以后端返回的
 * {@code riskLevel / applyKeyword / ruleErrors} 为准，前端不重复实现规则，
 * 避免两处规则漂移后出现「前端放行、后端拒绝」或更糟的反向情况。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */

/** 类别展示元数据 */
export interface CategoryMeta {
  label: string;
  color: string;
  /** 值输入框的示例提示 */
  placeholder: string;
  /** 一行说明改动落在哪里 */
  scope: string;
}

/** 类别元数据；未知类别走 fallback，保证页面不因新增类别而崩 */
export const CATEGORY_META: Record<string, CategoryMeta> = {
  sysctl: {
    label: '内核参数',
    color: 'blue',
    placeholder: '如 65535 / 1 / 1 0 0 0',
    scope: '写入 /etc/sysctl.d/ 的 drop-in，执行 sysctl --system 即时生效',
  },
  limits: {
    label: '资源限制',
    color: 'purple',
    placeholder: '如 65535 / unlimited / 1048576',
    scope: '写入 /etc/security/limits.d/ 的 drop-in，仅对新会话/新进程生效',
  },
  sshd: {
    label: 'SSH 服务',
    color: 'red',
    placeholder: '如 yes / no / 22',
    scope: '写入 /etc/ssh/sshd_config.d/ 的 drop-in，sshd -t 通过后 reload',
  },
  timesync: {
    label: '时间同步',
    color: 'cyan',
    placeholder: '如 ntp.aliyun.com / 2',
    scope: '按 provider 写入 chrony / systemd-timesyncd 的 drop-in 并重启服务',
  },
};

/** 类别顺序（与后端种子一致，后端已带 sort） */
export const CATEGORY_ORDER = ['sysctl', 'limits', 'sshd', 'timesync'];

export function categoryMeta(key?: string): CategoryMeta {
  if (key && CATEGORY_META[key]) {
    return CATEGORY_META[key] as CategoryMeta;
  }
  return {
    label: key || '未知类别',
    color: 'default',
    placeholder: '请输入参数值',
    scope: '写入该类别对应的托管片段',
  };
}

/** 风险级文案与颜色 */
export function riskMeta(level?: string): { color: string; label: string } {
  switch ((level || '').toUpperCase()) {
    case 'L3': {
      return { color: 'red', label: 'L3 高风险（需键入关键字）' };
    }
    case 'L2': {
      return { color: 'orange', label: 'L2 中风险' };
    }
    case 'L1': {
      return { color: 'green', label: 'L1 低风险' };
    }
    default: {
      return { color: 'default', label: level || '-' };
    }
  }
}

/** 操作类型文案与颜色 */
export function opMeta(op?: string): { color: string; label: string } {
  switch ((op || '').toUpperCase()) {
    case 'APPLY': {
      return { color: 'blue', label: '一键生效' };
    }
    case 'RESTORE': {
      return { color: 'gold', label: '按历史恢复' };
    }
    case 'UNMANAGE': {
      return { color: 'default', label: '停止托管' };
    }
    default: {
      return { color: 'default', label: op || '-' };
    }
  }
}

/** 结果文案与颜色（红色只在「失败」出现，避免与危险色混淆） */
export function resultMeta(result?: string): { color: string; label: string } {
  switch ((result || '').toUpperCase()) {
    case 'SUCCESS': {
      return { color: 'green', label: '成功' };
    }
    case 'ROLLED_BACK': {
      return { color: 'orange', label: '已自动回滚' };
    }
    case 'FAILED': {
      return { color: 'red', label: '失败' };
    }
    default: {
      return { color: 'default', label: result || '-' };
    }
  }
}

/** diff 行（后端格式：`-` 删除 / `+` 新增 / 空格 未变） */
export interface DiffLine {
  kind: 'add' | 'context' | 'del';
  text: string;
}

/**
 * 解析后端行级 diff。
 *
 * <p>不做「仅在变更行附近截断」这类美化：配置片段通常几十行，
 * 展示全文更利于核对我到底改了哪几行；后端已对超长内容做了上限保护。
 */
export function parseDiff(diff?: null | string): DiffLine[] {
  if (!diff) return [];
  return diff
    .replace(/\r\n/g, '\n')
    .split('\n')
    .filter((line, index, arr) => !(index === arr.length - 1 && line === ''))
    .map((line) => {
      if (line.startsWith('+')) return { kind: 'add' as const, text: line.slice(1) };
      if (line.startsWith('-')) return { kind: 'del' as const, text: line.slice(1) };
      return { kind: 'context' as const, text: line.replace(/^ /, '') };
    });
}

/** 耗时展示 */
export function formatDuration(ms?: null | number): string {
  if (ms === null || ms === undefined) return '-';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}

/** 生效值展示：null / 空 一律显示为「未读取」而不是空白，避免看起来像 0 */
export function displayValue(value?: null | string): string {
  if (value === null || value === undefined || value === '') return '-';
  return value;
}

/** 托管值展示：空 = 明确显示「不托管」，这是本模块的核心语义 */
export function displayManaged(value?: null | string): string {
  if (value === null || value === undefined || value === '') return '不托管';
  return value;
}
