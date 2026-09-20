import { requestClient } from '#/api/request';

/**
 * 工作台（dashboard/workspace）接口
 */
export namespace DashboardApi {
  /** SSH 登录记录 */
  export interface SshLogin {
    /** 登录时间（yyyy-MM-dd HH:mm:ss） */
    time: string;
    /** 登录用户 */
    username: string;
    /** 来源 IP */
    ip: string;
    /** 来源端口 */
    port: string;
    /** 认证方式：publickey / password */
    method: string;
  }

  /** 快捷导航服务 */
  export interface QuickService {
    /** systemd 服务名 */
    name: string;
    /** 展示名 */
    displayName: string;
    /** Web 管理端口（-1 表示未知） */
    port: number;
    /** Web 访问路径前缀 */
    path: string;
    /** 是否正在运行 */
    running: boolean;
  }

  /** 访问来源（按地区聚合） */
  export interface VisitSource {
    /** 地区标签 */
    region: string;
    /** 访问次数 */
    count: number;
  }
}

/**
 * 最近 10 次 SSH 登录成功记录
 */
export async function getSshLoginsApi() {
  return requestClient.get<DashboardApi.SshLogin[]>('/dashboard/ssh-logins');
}

/**
 * 快捷导航：运行中的已知 Web 服务
 */
export async function getQuickServicesApi() {
  return requestClient.get<DashboardApi.QuickService[]>('/dashboard/services');
}

/**
 * 访问来源：登录成功 IP 按地区聚合
 */
export async function getVisitSourcesApi() {
  return requestClient.get<DashboardApi.VisitSource[]>('/dashboard/visit-sources');
}
