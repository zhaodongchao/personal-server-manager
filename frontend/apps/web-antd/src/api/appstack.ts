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

  /**
   * 取号数据源（ID 生成器「自增 / 序列」方案真连库取号的目标库）。
   *
   * `passwordMasked` 固定为 ******，后端从不回显明文或密文；
   * 编辑时 `password` 留空表示不修改口令。
   */
  export interface IdSource {
    id?: string;
    name: string;
    /** POSTGRESQL / MYSQL */
    dbType: string;
    dbTypeLabel?: string;
    host: string;
    port: number;
    dbName: string;
    username: string;
    password?: string;
    passwordMasked?: string;
    tableName?: string;
    sequenceName?: string;
    autoInit?: boolean;
    status: boolean;
    remark?: string;
    /** 口令加密密钥是否可用；false 时无法保存数据源 */
    cipherReady?: boolean;
    createdAt?: string;
  }

  /** 取号数据源下拉项 */
  export interface IdSourceOption {
    value: string;
    label: string;
    dbType?: string;
    target?: string;
  }

  /** 取号数据源的环境状态 */
  export interface IdSourceStatus {
    cipherReady: boolean;
    allowedDatabases: string[];
    defaultSequence: string;
    defaultTable: string;
    autoPrefix: string;
  }

  /** 连通性探测结果 */
  export interface IdSourceProbe {
    ok: boolean;
    message: string;
    serverVersion?: string;
  }

  /** 取号对象初始化结果 */
  export interface IdSourceInit {
    message: string;
    target: string;
    serverVersion?: string;
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

// ==================== 取号数据源 ====================

export async function getIdSourcePageApi(params: {
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get('/appstack/id-source/page', { params });
}

export async function getIdSourceOptionsApi() {
  return requestClient.get<AppstackApi.IdSourceOption[]>('/appstack/id-source/options');
}

export async function getIdSourceStatusApi() {
  return requestClient.get<AppstackApi.IdSourceStatus>('/appstack/id-source/status');
}

export async function createIdSourceApi(body: Partial<AppstackApi.IdSource>) {
  return requestClient.post<string>('/appstack/id-source', body);
}

export async function updateIdSourceApi(id: string, body: Partial<AppstackApi.IdSource>) {
  return requestClient.put(`/appstack/id-source/${id}`, body);
}

export async function deleteIdSourceApi(id: string) {
  return requestClient.delete(`/appstack/id-source/${id}`);
}

/** 连通性探测（只读：select version()） */
export async function probeIdSourceApi(id: string) {
  return requestClient.post<AppstackApi.IdSourceProbe>(`/appstack/id-source/${id}/probe`);
}

/** 初始化取号对象（建序列 / 建自增表），幂等 */
export async function initIdSourceApi(id: string) {
  return requestClient.post<AppstackApi.IdSourceInit>(`/appstack/id-source/${id}/init`);
}
