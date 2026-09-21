/**
 * 防火墙页前端辅助（展示语义 + 生存线风险判定）
 *
 * <p>两处容易被忽略、但会直接导致事故的点，集中放在这里统一处理：
 * <ol>
 *   <li><b>动作与来源是两个字段。</b>ufw 的动作形如 {@code ALLOW IN}，早期实现把 {@code IN}
 *       混进了来源列，页面会出现「来源 = IN 172.238.101.222」的污染数据。现在 direction
 *       独立成列，展示时不再拼进来源。</li>
 *   <li><b>生存线判定要在前端先做一遍。</b>后端当然会再拦一次（错误码 5015），但与其让使用者
 *       点下去才被拒，不如在填表时就告诉他「这条规则会切断 SSH 22」，并要求键入确认关键字。
 *       前端的判定口径与后端 {@code FirewallService#requireGuard} 保持一致。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
import type { OpsApi } from '#/api';

/** 动作下拉（对应 ufw 的 allow/deny/reject/limit） */
export const ACTION_OPTIONS = [
  { label: '放行', value: 'allow' },
  { label: '拒绝（静默丢弃）', value: 'deny' },
  { label: '拒绝并回包', value: 'reject' },
  { label: '限速放行（防暴力破解）', value: 'limit' },
];

/** 协议下拉：any 对应 ufw 不写 /tcp、/udp 后缀的写法 */
export const PROTOCOL_OPTIONS = [
  { label: 'TCP', value: 'tcp' },
  { label: 'UDP', value: 'udp' },
  { label: 'TCP + UDP', value: 'any' },
];

/** 目标形态下拉：四种写法覆盖 ufw 的全部规则形态（含过去无法删除的范围/Anywhere 规则） */
export const TARGET_OPTIONS = [
  { label: '单端口', value: 'port' },
  { label: '端口范围', value: 'range' },
  { label: '多端口（逗号分隔）', value: 'multi' },
  { label: '任意端口（Anywhere）', value: 'any' },
];

/** 来源标记下拉（筛选用） */
export const PROVENANCE_OPTIONS = [
  { label: '面板写入', value: 'panel' },
  { label: 'Fail2Ban', value: 'fail2ban' },
  { label: '人工维护', value: 'manual' },
  { label: '未标注', value: 'unknown' },
];

export function actionColor(action?: string) {
  switch (action) {
    case 'allow': {
      return 'green';
    }
    case 'deny': {
      return 'red';
    }
    case 'reject': {
      return 'orange';
    }
    case 'limit': {
      return 'blue';
    }
    default: {
      return 'default';
    }
  }
}

export function actionLabel(action?: string) {
  switch (action) {
    case 'allow': {
      return '放行';
    }
    case 'deny': {
      return '拒绝';
    }
    case 'reject': {
      return '拒绝并回包';
    }
    case 'limit': {
      return '限速放行';
    }
    default: {
      return action || '-';
    }
  }
}

export function directionLabel(direction?: string) {
  switch (direction) {
    case 'in': {
      return '入站';
    }
    case 'out': {
      return '出站';
    }
    case 'fwd': {
      return '转发';
    }
    default: {
      return direction || '-';
    }
  }
}

export function toKindLabel(kind?: string) {
  switch (kind) {
    case 'port': {
      return '端口';
    }
    case 'range': {
      return '范围';
    }
    case 'multi': {
      return '多端口';
    }
    case 'any': {
      return '任意';
    }
    case 'app': {
      return '应用';
    }
    default: {
      return kind || '-';
    }
  }
}

export function sourceKindLabel(kind?: string) {
  switch (kind) {
    case 'ip': {
      return '单个 IP';
    }
    case 'cidr': {
      return '网段';
    }
    case 'any': {
      return '任意来源';
    }
    default: {
      return kind || '-';
    }
  }
}

/** 来源标记：面板写入可放心删，fail2ban 由外部程序托管、删除后会被重建 */
export function provenanceMeta(provenance?: string): {
  color: string;
  label: string;
} {
  switch (provenance) {
    case 'panel': {
      return { color: 'blue', label: '面板' };
    }
    case 'fail2ban': {
      return { color: 'purple', label: 'Fail2Ban' };
    }
    case 'manual': {
      return { color: 'cyan', label: '人工' };
    }
    default: {
      return { color: 'default', label: '未标注' };
    }
  }
}

/**
 * 从 ufw 的 {@code to} 字段反解出端口集合。
 *
 * @returns 端口数组；{@code null} 表示「任意端口」
 */
export function parsePorts(to?: string, kind?: string): number[] | null {
  if (!to) return null;
  if (kind === 'any' || /^anywhere$/i.test(to.trim())) return null;
  // 形态：Anywhere | 22/tcp | 80,443/tcp | 40000:40100/tcp | Nginx HTTP | 22/tcp on eth0
  let s = to.trim();
  const onIdx = s.indexOf(' on ');
  if (onIdx > 0) s = s.slice(0, onIdx);
  s = s.replace(/\(v6\)\s*$/i, '').trim();
  s = s.replace(/\/(tcp|udp)$/i, '').trim();

  if (/^\d+$/.test(s)) return [Number(s)];
  if (/^\d+\s*,\s*[\d,\s]+$/.test(s)) {
    return s
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
      .map(Number)
      .filter((n) => Number.isFinite(n));
  }
  const m = /^(\d+)\s*:\s*(\d+)$/.exec(s);
  if (m) {
    const a = Number(m[1]);
    const b = Number(m[2]);
    // 范围可能很大（如 30000:65535），只取端点参与生存线判定即可
    return [a, b];
  }
  return null;
}

/** 目标是否覆盖指定端口（口径同后端 coversTarget） */
export function covers(ports: number[] | null, port: number): boolean {
  if (ports === null) return true;
  if (!ports.length) return false;
  if (ports.length === 1) return ports[0] === port;
  const lo = Math.min(...ports);
  const hi = Math.max(...ports);
  return port >= lo && port <= hi || ports.includes(port);
}

/** 表单目标 → 端口集合（null = 任意） */
export function targetPorts(target?: OpsApi.FirewallRuleTarget): number[] | null {
  if (!target) return null;
  switch (target.kind) {
    case 'port': {
      return target.port ? [target.port] : [];
    }
    case 'range': {
      return target.port && target.portEnd ? [target.port, target.portEnd] : [];
    }
    case 'multi': {
      return target.ports?.filter((n) => Number.isFinite(n)) ?? [];
    }
    case 'any': {
      return null;
    }
    default: {
      return [];
    }
  }
}

export interface RiskHint {
  /** danger 会要求键入确认关键字；warn 仅提示 */
  level: 'warn' | 'danger';
  text: string;
  /** 需要使用者键入的确认关键字，如 `SSH 22` */
  keyword?: string;
}

/**
 * 新增规则的生存线风险：deny/reject 覆盖了 SSH 或面板端口即危险。
 *
 * <p>关键字口径与后端一致：SSH 端口取 {@code "SSH <port>"}，面板端口取 {@code "PANEL <port>"}。
 */
export function evaluateAddRisk(
  body: OpsApi.FirewallRuleBody,
  guard?: OpsApi.FirewallGuard | null,
): RiskHint | null {
  if (!guard) return null;
  const action = body.action ?? 'allow';
  if (action !== 'deny' && action !== 'reject') {
    if (action === 'limit') {
      const ports = targetPorts(body.target);
      const hit = (guard.sshPorts ?? []).find((p) => covers(ports, p));
      if (hit) {
        return {
          level: 'warn',
          text: `对 SSH 端口 ${hit} 限速会影响登录体验，暴力破解场景下也可能把自己挡在门外`,
        };
      }
    }
    return null;
  }
  const ports = targetPorts(body.target);
  if (ports !== null && ports.length === 0) return null;
  const sshHit = (guard.sshPorts ?? []).find((p) => covers(ports, p));
  if (sshHit) {
    return {
      level: 'danger',
      keyword: `SSH ${sshHit}`,
      text: `该规则会拒绝 SSH 端口 ${sshHit} 的访问，生效后可能无法远程登录。确认请键入 SSH ${sshHit}`,
    };
  }
  const panelHit = (guard.panelPorts ?? []).find((p) => covers(ports, p));
  if (panelHit) {
    return {
      level: 'danger',
      keyword: `PANEL ${panelHit}`,
      text: `该规则会拒绝面板端口 ${panelHit} 的访问，生效后面板将无法打开。确认请键入 PANEL ${panelHit}`,
    };
  }
  return null;
}

/** 删除放行规则同样可能切断SSH/面板 */
export function evaluateDeleteRisk(
  rule: OpsApi.FirewallRule,
  guard?: OpsApi.FirewallGuard | null,
): RiskHint | null {
  if (!guard) return null;
  if (rule.provenance === 'fail2ban') {
    return {
      level: 'warn',
      text: '该规则由 Fail2Ban 维护，删除后会被自动重建，通常不需要手工处理',
    };
  }
  if (rule.action !== 'allow') return null;
  const ports = parsePorts(rule.to, rule.toKind);
  if (ports !== null && ports.length === 0) return null;
  const sshHit = (guard.sshPorts ?? []).find((p) => covers(ports, p));
  if (sshHit) {
    return {
      level: 'danger',
      keyword: `SSH ${sshHit}`,
      text: `这是 SSH 端口 ${sshHit} 的放行规则，删除后可能无法远程登录。确认请键入 SSH ${sshHit}`,
    };
  }
  const panelHit = (guard.panelPorts ?? []).find((p) => covers(ports, p));
  if (panelHit) {
    return {
      level: 'danger',
      keyword: `PANEL ${panelHit}`,
      text: `这是面板端口 ${panelHit} 的放行规则，删除后面板将无法打开。确认请键入 PANEL ${panelHit}`,
    };
  }
  return null;
}

/** 目标形态 → ufw 片段（用于命令预览，让使用者在点确认前就知道会执行什么） */
export function describeTarget(target?: OpsApi.FirewallRuleTarget): string {
  if (!target) return '';
  switch (target.kind) {
    case 'port': {
      return String(target.port ?? '');
    }
    case 'range': {
      return `${target.port ?? ''}:${target.portEnd ?? ''}`;
    }
    case 'multi': {
      return (target.ports ?? []).join(',');
    }
    case 'any': {
      return 'Anywhere';
    }
    default: {
      return '';
    }
  }
}

/**
 * 拼出将要执行的 ufw 命令预览（与后端 argv 一致）。
 *
 * <p>把命令原文提前展示出来是这次重构的一个刻意选择：防火墙是唯一一个「改错就失联」的模块，
 * 让使用者在按下确认前看到 {@code ufw deny proto tcp to any port 22} 这样的原文，
 * 比任何提示文案都更可靠。
 */
export function previewCommand(body: OpsApi.FirewallRuleBody): string {
  const target = describeTarget(body.target);
  const proto = !body.protocol || body.protocol === 'any' ? '' : 'proto tcp ';
  const portPart =
    body.target?.kind === 'any' ? '' : `port ${target}`.trim();
  const from = body.source ? `from ${body.source} ` : '';
  const comment = body.comment ? ` comment '${body.comment}'` : '';
  return [
    'ufw',
    body.action ?? 'allow',
    proto + from + (body.target?.kind === 'any' ? '' : portPart),
    comment,
  ]
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 变更记录的操作类型 → 中文 */
export function opLabel(op?: string) {
  switch (op) {
    case 'ADD_RULE': {
      return '新增规则';
    }
    case 'DELETE_RULE': {
      return '删除规则';
    }
    case 'ENABLE': {
      return '启用防火墙';
    }
    case 'DISABLE': {
      return '停用防火墙';
    }
    case 'SET_DEFAULT': {
      return '设置默认策略';
    }
    case 'RELOAD': {
      return '重载';
    }
    case 'ROLLBACK': {
      return '回滚';
    }
    default: {
      return op || '-';
    }
  }
}

/** 变更记录的结果 */
export function resultMeta(change: OpsApi.FirewallChange): {
  color: string;
  label: string;
} {
  if (change.rolledBack === 1) return { color: 'default', label: '已回滚' };
  if (change.result === 1) return { color: 'red', label: '失败' };
  return { color: 'green', label: '成功' };
}

/** diff 行文本 → 展示色（+ 新增绿、- 删除红、* 修改蓝） */
export function diffLineClass(line: string): string {
  if (line.startsWith('+')) return 'diff-add';
  if (line.startsWith('-')) return 'diff-del';
  if (line.startsWith('*')) return 'diff-mod';
  return '';
}

/** 秒 → mm:ss */
export function fmtSeconds(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

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
