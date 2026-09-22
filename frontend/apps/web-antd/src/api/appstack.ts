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
