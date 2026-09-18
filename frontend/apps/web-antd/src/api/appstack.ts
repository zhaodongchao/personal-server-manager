import { requestClient } from '#/api/request';

export namespace AppstackApi {
  /** Docker 容器 */
  export interface ContainerInfo {
    id: string;
    name: string;
    image: string;
    state: string;
    status: string;
    ports: string;
  }

  /** Docker 镜像 */
  export interface ImageInfo {
    id: string;
    tag: string;
    size: number;
    created: number;
  }

  /** 网站 */
  export interface Website {
    id?: string;
    domain: string;
    siteName: string;
    siteType: 'proxy' | 'static';
    upstream?: string;
    staticRoot?: string;
    sslEnabled?: number;
    certPath?: string;
    keyPath?: string;
    confPath?: string;
    status?: number;
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
  }

  /** 数据库 */
  export interface Database {
    id?: string;
    dbName: string;
    dbUser: string;
    charset: string;
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
  }

  /** 建库结果（密码仅返回一次） */
  export interface DatabaseCreateResult {
    dbName: string;
    username: string;
    password: string;
    charset: string;
  }
}

// ==================== Docker ====================

export async function getContainerListApi() {
  return requestClient.get<AppstackApi.ContainerInfo[]>('/appstack/docker/containers');
}

export async function containerActionApi(id: string, action: string) {
  return requestClient.post(`/appstack/docker/containers/${id}/${action}`);
}

export async function getImageListApi() {
  return requestClient.get<AppstackApi.ImageInfo[]>('/appstack/docker/images');
}

export async function pullImageApi(image: string) {
  return requestClient.post('/appstack/docker/images/pull', { image });
}

// ==================== Nginx 网站 ====================

export async function getWebsitePageApi(params: {
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get('/appstack/website/page', { params });
}

export async function getNginxStatusApi() {
  return requestClient.get<boolean>('/appstack/website/nginx-status');
}

export async function getWebsiteConfApi(id: string) {
  return requestClient.get<string>(`/appstack/website/${id}/conf`);
}

export async function createWebsiteApi(body: AppstackApi.Website) {
  return requestClient.post('/appstack/website', body);
}

export async function updateWebsiteApi(body: AppstackApi.Website) {
  return requestClient.put('/appstack/website', body);
}

export async function toggleWebsiteApi(id: string, status: number) {
  return requestClient.put(`/appstack/website/${id}/status/${status}`);
}

export async function deleteWebsiteApi(id: string) {
  return requestClient.delete(`/appstack/website/${id}`);
}

// ==================== MySQL 数据库 ====================

export async function getDatabasePageApi(params: {
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get('/appstack/database/page', { params });
}

export async function getDatabaseCharsetsApi() {
  return requestClient.get<string[]>('/appstack/database/charsets');
}

export async function createDatabaseApi(body: {
  dbName: string;
  charset: string;
  remark?: string;
}) {
  return requestClient.post<AppstackApi.DatabaseCreateResult>('/appstack/database', body);
}

export async function deleteDatabaseApi(id: string) {
  return requestClient.delete(`/appstack/database/${id}`);
}

export async function backupDatabaseApi(id: string) {
  return requestClient.post<string>(`/appstack/database/${id}/backup`);
}

export async function restoreDatabaseApi(id: string, backupFile: string) {
  return requestClient.post(`/appstack/database/${id}/restore`, { backupFile });
}
