/**
 * Nginx 管理页共用展示辅助（标签、颜色、选项、时间格式化）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */

// ==================== 选项 ====================

export const SITE_TYPE_OPTIONS = [
  { label: '反向代理', value: 'proxy' },
  { label: '静态站点', value: 'static' },
];

export const SSL_MODE_OPTIONS = [
  { label: '关闭 HTTPS', value: 'off' },
  { label: "Let's Encrypt", value: 'letsencrypt' },
  { label: '手动证书', value: 'custom' },
];

export const STRATEGY_OPTIONS = [
  { label: '轮询 (round_robin)', value: 'round_robin' },
  { label: '最少连接 (least_conn)', value: 'least_conn' },
  { label: '源地址哈希 (ip_hash)', value: 'ip_hash' },
];

export const LOC_TYPE_OPTIONS = [
  { label: '反向代理', value: 'proxy' },
  { label: '静态目录', value: 'static' },
  { label: '重定向', value: 'redirect' },
  { label: '拒绝访问', value: 'deny' },
];

export const STREAM_PROTOCOL_OPTIONS = [
  { label: 'TCP', value: 'tcp' },
  { label: 'UDP', value: 'udp' },
];

export const CERT_TYPE_OPTIONS = [
  { label: "Let's Encrypt (ACME)", value: 'letsencrypt' },
  { label: '手动上传', value: 'custom' },
];

export const ACME_MODE_OPTIONS = [
  { label: 'HTTP-01（webroot 校验）', value: 'http01' },
  { label: 'DNS-01（通配符，两步 TXT）', value: 'dns01' },
];

// ==================== 标签与颜色 ====================

export function siteTypeLabel(type?: string): string {
  return type === 'static' ? '静态' : type === 'proxy' ? '反代' : (type || '-');
}

export function siteTypeColor(type?: string): string {
  return type === 'static' ? 'purple' : type === 'proxy' ? 'blue' : 'default';
}

export function sslModeLabel(mode?: string): string {
  switch (mode) {
    case 'letsencrypt': {
      return "Let's Encrypt";
    }
    case 'custom': {
      return '手动证书';
    }
    default: {
      return 'HTTP';
    }
  }
}

export function sslModeColor(mode?: string): string {
  switch (mode) {
    case 'letsencrypt': {
      return 'green';
    }
    case 'custom': {
      return 'blue';
    }
    default: {
      return 'default';
    }
  }
}

export function strategyLabel(strategy?: string): string {
  switch (strategy) {
    case 'least_conn': {
      return '最少连接';
    }
    case 'ip_hash': {
      return 'IP 哈希';
    }
    default: {
      return '轮询';
    }
  }
}

export function certStatusLabel(status?: string): string {
  switch (status) {
    case 'expiring': {
      return '即将到期';
    }
    case 'expired': {
      return '已过期';
    }
    case 'pending': {
      return '申请中';
    }
    default: {
      return '有效';
    }
  }
}

export function certStatusColor(status?: string): string {
  switch (status) {
    case 'expiring': {
      return 'orange';
    }
    case 'expired': {
      return 'red';
    }
    case 'pending': {
      return 'processing';
    }
    default: {
      return 'green';
    }
  }
}

export function streamProtocolLabel(protocol?: string): string {
  return (protocol ?? 'tcp').toUpperCase();
}

export function streamProtocolColor(protocol?: string): string {
  return protocol === 'udp' ? 'purple' : 'blue';
}

/** 变更记录的操作类型 → 中文 */
export function opLabel(op?: string): string {
  const map: Record<string, string> = {
    CREATE_SITE: '新建站点',
    UPDATE_SITE: '更新站点',
    DELETE_SITE: '删除站点',
    TOGGLE: '启停',
    CREATE_UPSTREAM: '新建上游',
    UPDATE_UPSTREAM: '更新上游',
    DELETE_UPSTREAM: '删除上游',
    CREATE_STREAM: '新建转发',
    UPDATE_STREAM: '更新转发',
    DELETE_STREAM: '删除转发',
    TOGGLE_STREAM: '启停转发',
    ISSUE_CERT: '申请证书',
    RENEW_CERT: '续期证书',
    UPLOAD_CERT: '上传证书',
    DELETE_CERT: '删除证书',
    RELOAD: '重载',
    TEST: '配置校验',
    ROLLBACK: '回滚',
  };
  return map[op ?? ''] ?? (op || '-');
}

/** 变更记录的结果 */
export function resultMeta(change: { rolledBack?: number; result?: number }): {
  color: string;
  label: string;
} {
  if (change.rolledBack === 1) return { color: 'default', label: '已回滚' };
  if (change.result === 1) return { color: 'red', label: '失败' };
  return { color: 'green', label: '成功' };
}

// ==================== 时间与格式化 ====================

/**
 * 时间格式化。
 *
 * <p>后端返回的是「本地时间但没带时区标记」的字符串（如 {@code 2026-09-21 14:03:22}），
 * 直接 {@code new Date(s)} 在部分浏览器会被当成 UTC 解析而差 8 小时，因此统一补 {@code T}。
 */
export function fmtTime(value?: string): string {
  if (!value) return '-';
  const s = value.includes('T') ? value : value.replace(' ', 'T');
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 域名列表（逗号分隔字符串 / 数组）→ 首个主域 */
export function primaryDomain(domains?: string): string {
  if (!domains) return '-';
  return domains.split(',').map((s) => s.trim()).filter(Boolean)[0] ?? '-';
}

/** 解析上游组 servers_json → 展示文本 */
export function describeServers(serversJson?: string): string {
  if (!serversJson) return '-';
  try {
    const arr = JSON.parse(serversJson) as Array<{ host: string; port: number }>;
    return arr.map((s) => `${s.host}:${s.port}`).join(', ');
  } catch {
    return '-';
  }
}

/** 解析站点 locations_json → 数量 */
export function locationCount(locationsJson?: string): number {
  if (!locationsJson) return 0;
  try {
    const arr = JSON.parse(locationsJson);
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}
