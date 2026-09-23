/**
 * 快捷导航访问地址拼装（管理页列表与工作台共用同一套规则）。
 *
 * <p>拼装口径（与后端 QuickNavService.normalizeDomain 配套）：
 * <ul>
 *   <li>协议：https=1 走 https，否则 http；</li>
 *   <li>主机：domain 留空时跟随面板当前访问域名（浏览器 hostname）；</li>
 *   <li>端口：port<=0 不拼；配了域名时跳过协议默认端口（http 80 / https 443），
 *       免得https 站点被拼出 xxx:443 这种多余端口；域名自带端口时不重复拼。</li>
 *   <li>路径：path 保证以 / 开头，没有则补 ''。</li>
 * </ul>
 */
export interface QuickNavTarget {
  domain?: string;
  https?: number;
  port?: number;
  path?: string;
}

const DEFAULT_PORT = { http: 80, https: 443 } as const;

/** 完整访问地址，用于跳转 */
export function quickNavUrl(item: QuickNavTarget): string {
  const https = item.https === 1;
  const scheme = https ? 'https' : 'http';
  const domain = (item.domain ?? '').trim();
  const host = domain || window.location.hostname;
  const path = normalizePath(item.path);

  // 域名里已经写了端口（如 10.0.0.5:8080）或用的 IPv6 字面量，直接原样用
  if (domain && (domain.includes(':') || domain.startsWith('['))) {
    return `${scheme}://${domain}${path}`;
  }

  const port = item.port ?? -1;
  const defaultPort = https ? DEFAULT_PORT.https : DEFAULT_PORT.http;
  // 没有配域名时沿用旧行为（面板域名 + 端口），配了域名则省掉协议默认端口
  const needPort = port > 0 && (!domain || port !== defaultPort);
  return `${scheme}://${host}${needPort ? `:${port}` : ''}${path}`;
}

/** 列表展示用的简化地址（去掉协议头） */
export function quickNavHostLabel(item: QuickNavTarget): string {
  const url = quickNavUrl(item);
  const scheme = item.https === 1 ? 'https' : 'http';
  return url.startsWith(`${scheme}://`) ? url.slice(`${scheme}://`.length) : url;
}

function normalizePath(raw?: string): string {
  const path = (raw ?? '').trim();
  if (!path) return '';
  return path.startsWith('/') ? path : `/${path}`;
}
