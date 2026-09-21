import { requestClient } from '#/api/request';

/**
 * Nginx 管理 API 与类型定义。
 *
 * <p>与后端 `com.serverpanel.ops.controller.NginxController`、`dto/*`、`entity/*`
 * 一一对应。所有写操作均返回 {@link NginxApi.NginxActionResult}（命令描述 + 差异 +
 * 回滚快照 ID），危险操作（删站 / 删实例 / 删证书 / 删转发 / 回滚）后端要求
 * `confirm` 关键字二次确认（错误码 6011 NGINX_GUARD_TRIGGERED）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
export namespace NginxApi {
  /** 通用分页结果（与后端 PageResult<T> 对应） */
  export interface Page<T> {
    records: T[];
    total: number;
    pageNum: number;
    pageSize: number;
  }

  // ==================== 实例 ====================

  /** nginx 实例（可配置、默认自动探测） */
  export interface NginxInstance {
    id?: string;
    name: string;
    /** auto（探测生成）/ manual（手工指定） */
    detectMode?: 'auto' | 'manual';
    /** nginx 可执行路径 */
    binary?: string;
    /** --prefix */
    prefix?: string;
    /** 主配置 nginx.conf 绝对路径 */
    confPath?: string;
    /** 面板托管站点目录 */
    managedDir?: string;
    /** stream 托管目录 */
    streamDir?: string;
    /** 证书目录 */
    certDir?: string;
    /** ACME HTTP-01 webroot */
    acmeWebroot?: string;
    /** 日志目录 */
    logDir?: string;
    /** 是否默认实例 */
    defaultFlag?: number;
    /** 1 启用 0 停用 */
    status?: number;
    remark?: string;
    createdAt?: string;
  }

  /** 实例请求体（手工指定 / 探测结果回写） */
  export interface NginxInstanceBody {
    id?: string;
    name: string;
    detectMode?: string;
    binary?: string;
    prefix?: string;
    confPath?: string;
    managedDir?: string;
    streamDir?: string;
    certDir?: string;
    acmeWebroot?: string;
    logDir?: string;
    status?: number;
    remark?: string;
  }

  // ==================== 状态 ====================

  /** nginx 运行态 + 实例信息 + 能力 */
  export interface NginxStatus {
    channelOk: boolean;
    channelMessage?: string;
    instanceExists: boolean;
    instanceId?: string;
    instanceName?: string;
    nginxAvailable: boolean;
    nginxVersion?: string;
    nginxBinary?: string;
    confPath?: string;
    configValid: boolean;
    configMessage?: string;
    certbotVersion?: string;
    siteCount: number;
    upstreamCount: number;
    streamCount: number;
    certCount: number;
    /** 即将到期（<=30 天）证书域名 */
    expiringCerts?: string[];
  }

  // ==================== 站点 ====================

  /** 单个自定义 location 块 */
  export interface NginxLocation {
    path?: string;
    /** proxy / static / redirect / deny */
    type?: 'proxy' | 'static' | 'redirect' | 'deny';
    /** proxy 类型：上游地址 */
    upstream?: string;
    /** static 类型：根目录 */
    staticRoot?: string;
    /** redirect 类型：目标 URL */
    redirectTarget?: string;
  }

  /** 站点实体 */
  export interface NginxSite {
    id?: string;
    instanceId?: string;
    name: string;
    /** 域名列表，逗号分隔，首个为主域 */
    domains?: string;
    /** proxy / static */
    siteType?: 'proxy' | 'static';
    upstreamId?: string;
    /** 内联上游，如 http://127.0.0.1:3000 */
    upstreamInline?: string;
    staticRoot?: string;
    /** off / letsencrypt / custom */
    sslMode?: 'off' | 'letsencrypt' | 'custom';
    certId?: string;
    /** 80 -> 443 强制跳转 */
    httpRedirect?: number;
    hsts?: number;
    /** 自定义 location 列表 JSON */
    locationsJson?: string;
    confPath?: string;
    confHash?: string;
    /** 1 启用 0 停用 */
    status?: number;
    remark?: string;
    createdAt?: string;
  }

  /** 站点请求体 */
  export interface NginxSiteBody {
    id?: string;
    instanceId?: string;
    name: string;
    /** 域名列表，首个为主域 */
    domains?: string[];
    siteType?: string;
    upstreamId?: string;
    upstreamInline?: string;
    staticRoot?: string;
    sslMode?: string;
    certId?: string;
    httpRedirect?: boolean;
    hsts?: boolean;
    locations?: NginxLocation[];
    remark?: string;
  }

  // ==================== 上游组 ====================

  /** 单个后端 */
  export interface UpstreamServer {
    host: string;
    port: number;
    weight?: number;
    maxFails?: number;
    backup?: boolean;
  }

  /** 上游组实体 */
  export interface NginxUpstream {
    id?: string;
    instanceId?: string;
    name: string;
    /** round_robin / least_conn / ip_hash */
    strategy?: 'round_robin' | 'least_conn' | 'ip_hash';
    /** [{"host":...,"port":...,"weight":...,"max_fails":...,"backup":...}] */
    serversJson?: string;
    keepalive?: number;
    remark?: string;
    createdAt?: string;
  }

  /** 上游组请求体 */
  export interface NginxUpstreamBody {
    id?: string;
    instanceId?: string;
    name: string;
    strategy?: string;
    servers?: UpstreamServer[];
    keepalive?: number;
    remark?: string;
  }

  // ==================== 四层转发 ====================

  /** 四层转发实体 */
  export interface NginxStream {
    id?: string;
    instanceId?: string;
    name: string;
    /** tcp / udp */
    protocol?: 'tcp' | 'udp';
    listenPort?: number;
    upstreamHost?: string;
    upstreamPort?: number;
    proxyTimeout?: number;
    confPath?: string;
    confHash?: string;
    /** 1 启用 0 停用 */
    status?: number;
    remark?: string;
    createdAt?: string;
  }

  /** 四层转发请求体 */
  export interface NginxStreamBody {
    id?: string;
    instanceId?: string;
    name: string;
    protocol?: string;
    listenPort?: number;
    upstreamHost?: string;
    upstreamPort?: number;
    proxyTimeout?: number;
    remark?: string;
  }

  // ==================== 证书 ====================

  /** 证书实体 */
  export interface NginxCert {
    id?: string;
    instanceId?: string;
    domain: string;
    /** letsencrypt / custom */
    type?: 'letsencrypt' | 'custom';
    certPath?: string;
    keyPath?: string;
    issuer?: string;
    notBefore?: string;
    notAfter?: string;
    autoRenew?: number;
    lastRenewAt?: string;
    /** valid / expiring / expired / pending */
    status?: 'valid' | 'expiring' | 'expired' | 'pending';
    remark?: string;
    createdAt?: string;
  }

  /** 证书请求体（ACME 申请 / 手动上传） */
  export interface NginxCertBody {
    id?: string;
    instanceId?: string;
    domain?: string;
    type?: string;
    /** ACME 申请用邮箱 */
    email?: string;
    /** ACME 模式 http01 / dns01 */
    mode?: string;
    /** DNS-01 二段确认值 */
    dnsTxt?: string;
    autoRenew?: number;
    /** 手动证书：证书内容 */
    certContent?: string;
    /** 手动证书：私钥内容 */
    keyContent?: string;
  }

  // ==================== 变更 ====================

  /** 变更快照 */
  export interface NginxChange {
    id?: string;
    instanceId?: string;
    op?: string;
    /** site / upstream / stream / cert */
    targetType?: string;
    targetId?: string;
    /** 受影响的配置文件绝对路径 */
    confPath?: string;
    beforeConf?: string;
    afterConf?: string;
    rollbackConf?: string;
    rolledBack?: number;
    /** 0 成功 1 失败 */
    result?: number;
    errorMsg?: string;
    operator?: string;
    operatorIp?: string;
    createdAt?: string;
  }

  // ==================== 操作结果 ====================

  /** 写操作统一结果 */
  export interface NginxActionResult {
    /** 本次执行的关键命令描述 */
    command?: string;
    /** 变更前后差异（简版） */
    diff?: string;
    /** 是否已记录可回滚快照 */
    rollbackable?: boolean;
    /** 变更记录 ID */
    changeId?: string;
    message?: string;
  }
}

// ==================== 实例 ====================

/** 实例列表 */
export async function getNginxInstancesApi() {
  return requestClient.get<NginxApi.NginxInstance[]>('/ops/nginx/instance/list');
}

/** 探测主机 nginx（不落库） */
export async function detectNginxInstanceApi() {
  return requestClient.get<NginxApi.NginxInstanceBody>('/ops/nginx/instance/detect');
}

/** 新建实例 */
export async function saveNginxInstanceApi(body: NginxApi.NginxInstanceBody) {
  return requestClient.post<NginxApi.NginxInstance>('/ops/nginx/instance', body);
}

/** 更新实例 */
export async function updateNginxInstanceApi(body: NginxApi.NginxInstanceBody) {
  return requestClient.put<NginxApi.NginxInstance>('/ops/nginx/instance', body);
}

/** 删除实例（L3，需 confirm） */
export async function deleteNginxInstanceApi(id: string, confirm?: string) {
  return requestClient.request(`/ops/nginx/instance/${id}`, {
    method: 'DELETE',
    data: { confirm },
  });
}

/** 设为默认实例 */
export async function setDefaultNginxInstanceApi(id: string) {
  return requestClient.put(`/ops/nginx/instance/${id}/default`);
}

// ==================== 状态 / 现有站点 / 预览 ====================

/** 运行态 + 版本 + 能力（命名加 Ops 前缀，避免与 appstack 旧 nginx 状态接口重名） */
export async function getOpsNginxStatusApi(instanceId?: string) {
  return requestClient.get<NginxApi.NginxStatus>('/ops/nginx/status', {
    params: { instanceId },
  });
}

/** 宝塔既有 vhost 站点（只读） */
export async function getNginxExistingApi(instanceId?: string) {
  return requestClient.get<Record<string, unknown>[]>('/ops/nginx/existing', {
    params: { instanceId },
  });
}

/** 渲染预览（不落盘） */
export async function previewNginxSiteApi(body: NginxApi.NginxSiteBody) {
  return requestClient.post<string>('/ops/nginx/site/preview', body);
}

// ==================== 站点 ====================

/** 站点分页 */
export async function getNginxSitePageApi(params: {
  instanceId?: string;
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return requestClient.get<NginxApi.Page<NginxApi.NginxSite>>('/ops/nginx/site/page', {
    params,
  });
}

/** 新建站点 */
export async function createNginxSiteApi(body: NginxApi.NginxSiteBody) {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/site', body);
}

/** 更新站点 */
export async function updateNginxSiteApi(body: NginxApi.NginxSiteBody) {
  return requestClient.put<NginxApi.NginxActionResult>('/ops/nginx/site', body);
}

/** 删除站点（L3，需 confirm） */
export async function deleteNginxSiteApi(id: string, confirm?: string) {
  return requestClient.request<NginxApi.NginxActionResult>(`/ops/nginx/site/${id}`, {
    method: 'DELETE',
    data: { confirm },
  });
}

/** 启停站点 */
export async function toggleNginxSiteApi(id: string, status: number) {
  return requestClient.put<NginxApi.NginxActionResult>(
    `/ops/nginx/site/${id}/status/${status}`,
  );
}

/** 站点已渲染配置原文 */
export async function getNginxSiteConfApi(id: string) {
  return requestClient.get<string>(`/ops/nginx/site/${id}/conf`);
}

// ==================== 上游组 ====================

/** 上游组分页 */
export async function getNginxUpstreamPageApi(params: {
  instanceId?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<NginxApi.Page<NginxApi.NginxUpstream>>(
    '/ops/nginx/upstream/page',
    { params },
  );
}

/** 新建上游组 */
export async function saveNginxUpstreamApi(body: NginxApi.NginxUpstreamBody) {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/upstream', body);
}

/** 更新上游组 */
export async function updateNginxUpstreamApi(body: NginxApi.NginxUpstreamBody) {
  return requestClient.put<NginxApi.NginxActionResult>('/ops/nginx/upstream', body);
}

/** 删除上游组（L3，需 confirm） */
export async function deleteNginxUpstreamApi(id: string, confirm?: string) {
  return requestClient.request<NginxApi.NginxActionResult>(
    `/ops/nginx/upstream/${id}`,
    { method: 'DELETE', data: { confirm } },
  );
}

// ==================== 四层转发 ====================

/** 四层转发分页 */
export async function getNginxStreamPageApi(params: {
  instanceId?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<NginxApi.Page<NginxApi.NginxStream>>(
    '/ops/nginx/stream/page',
    { params },
  );
}

/** 新建四层转发 */
export async function saveNginxStreamApi(body: NginxApi.NginxStreamBody) {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/stream', body);
}

/** 更新四层转发 */
export async function updateNginxStreamApi(body: NginxApi.NginxStreamBody) {
  return requestClient.put<NginxApi.NginxActionResult>('/ops/nginx/stream', body);
}

/** 删除四层转发（L3，需 confirm） */
export async function deleteNginxStreamApi(id: string, confirm?: string) {
  return requestClient.request<NginxApi.NginxActionResult>(
    `/ops/nginx/stream/${id}`,
    { method: 'DELETE', data: { confirm } },
  );
}

/** 启停四层转发 */
export async function toggleNginxStreamApi(id: string, status: number) {
  return requestClient.put<NginxApi.NginxActionResult>(
    `/ops/nginx/stream/${id}/status/${status}`,
  );
}

// ==================== 证书 ====================

/** 证书分页 */
export async function getNginxCertPageApi(params: {
  instanceId?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<NginxApi.Page<NginxApi.NginxCert>>('/ops/nginx/cert/page', {
    params,
  });
}

/** ACME 申请证书 */
export async function issueNginxCertApi(body: NginxApi.NginxCertBody) {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/cert/issue', body);
}

/** 续期证书 */
export async function renewNginxCertApi(id: string) {
  return requestClient.post<NginxApi.NginxActionResult>(`/ops/nginx/cert/${id}/renew`);
}

/** 证书状态（含到期日） */
export async function getNginxCertStatusApi(id: string) {
  return requestClient.get<NginxApi.NginxCert>(`/ops/nginx/cert/${id}/status`);
}

/** 手动上传证书 */
export async function uploadNginxCertApi(body: NginxApi.NginxCertBody) {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/cert/upload', body);
}

/** 删除证书（L3，需 confirm） */
export async function deleteNginxCertApi(id: string, confirm?: string) {
  return requestClient.request<NginxApi.NginxActionResult>(`/ops/nginx/cert/${id}`, {
    method: 'DELETE',
    data: { confirm },
  });
}

// ==================== 日志 ====================

/** 可查看的日志文件清单 */
export async function getNginxLogListApi(instanceId?: string) {
  return requestClient.get<Record<string, unknown>[]>('/ops/nginx/log/list', {
    params: { instanceId },
  });
}

/** 日志 tail */
export async function getNginxLogTailApi(params: {
  instanceId?: string;
  file: string;
  lines?: number;
}) {
  return requestClient.get<string[]>('/ops/nginx/log/tail', { params });
}

// ==================== 变更历史与回滚 ====================

/** 变更历史分页 */
export async function getNginxChangePageApi(params: {
  instanceId?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<NginxApi.Page<NginxApi.NginxChange>>(
    '/ops/nginx/change/page',
    { params },
  );
}

/** 单次变更详情 */
export async function getNginxChangeDetailApi(id: string) {
  return requestClient.get<NginxApi.NginxChange>(`/ops/nginx/change/${id}`);
}

/** 回滚某次变更（L3，需 confirm） */
export async function rollbackNginxChangeApi(id: string, confirm?: string) {
  return requestClient.post<NginxApi.NginxActionResult>(
    `/ops/nginx/change/${id}/rollback`,
    { confirm },
  );
}

// ==================== 运维动作 ====================

/** nginx -t && nginx -s reload */
export async function reloadNginxApi() {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/reload');
}

/** nginx -t 配置校验 */
export async function testNginxApi() {
  return requestClient.post<NginxApi.NginxActionResult>('/ops/nginx/test');
}
