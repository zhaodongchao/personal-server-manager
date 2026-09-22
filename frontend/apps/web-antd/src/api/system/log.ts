import { requestClient } from '#/api/request';

export namespace LogApi {
  export interface AuditLog {
    id: string;
    module: string;
    /** 动作，如 user:add、access:denied（越权尝试） */
    action: string;
    operator: string;
    /** 类#方法；越权记录为 "-" */
    method?: string;
    uri?: string;
    requestMethod?: string;
    params?: string;
    ip?: string;
    /** 结果类别码：0 成功 / 403 越权或无权限 / 500 执行异常 */
    resultCode?: number;
    /** 业务错误码，如 6039、1010；成功为 null */
    bizCode?: number;
    /** 1 高危 0 普通 */
    risky?: number;
    error?: string;
    /** 耗时毫秒 */
    costMs?: number;
    userAgent?: string;
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
  bizCode?: number;
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
