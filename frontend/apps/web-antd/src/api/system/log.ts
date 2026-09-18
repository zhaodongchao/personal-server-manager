import { requestClient } from '#/api/request';

export namespace LogApi {
  export interface AuditLog {
    id: string;
    module: string;
    action: string;
    operator: string;
    method?: string;
    uri?: string;
    ip?: string;
    risky?: number;
    costMs?: number;
    params?: string;
    error?: string;
    createdAt: string;
  }

  export interface LoginLog {
    id: string;
    username: string;
    ip?: string;
    status: number;
    message?: string;
    userAgent?: string;
    createdAt: string;
  }
}

/** 审计日志分页 */
export async function getAuditLogPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  operator?: string;
  module?: string;
}) {
  return requestClient.get('/system/audit-log/page', { params });
}

/** 登录日志分页 */
export async function getLoginLogPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  username?: string;
  status?: number;
}) {
  return requestClient.get('/system/login-log/page', { params });
}
