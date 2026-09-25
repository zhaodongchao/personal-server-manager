import { requestClient } from '#/api/request';

export namespace BaseDataApi {
  /** 通用分页响应 */
  export interface PageResult<T> {
    records?: T[];
    total?: number;
  }

  /** 行政区划树节点（懒加载） */
  export interface RegionNode {
    id: string;
    code: string;
    name: string;
    level: number;
    status: number;
    leaf: boolean;
    children?: null | RegionNode[];
  }

  /** 行政区划分页行 */
  export interface RegionRow {
    id: string;
    code: string;
    name: string;
    shortName: null | string;
    level: number;
    parentCode: string;
    status: number;
    updatedAt?: string;
  }

  /** 手机号段 */
  export interface PhoneSegment {
    id: string;
    prefix: string;
    operator: string;
    segType: number;
    note: null | string;
  }

  /** 手机号段请求体 */
  export interface PhoneSegmentBody {
    id?: string;
    prefix: string;
    operator: string;
    segType: number;
    note?: null | string;
  }

  /** 银行卡 BIN */
  export interface BankBin {
    id: string;
    bin: string;
    bankName: string;
    bankShort: null | string;
    cardType: number;
    cardLen: number;
    note: null | string;
  }

  /** BIN 请求体 */
  export interface BankBinBody {
    id?: string;
    bin: string;
    bankName: string;
    bankShort?: null | string;
    cardType: number;
    cardLen: number;
    note?: null | string;
  }

  /** 单类型同步状态 */
  export interface TypeStatus {
    dataType: string;
    rows: number;
    ready: boolean;
    running: boolean;
    lastSyncAt: null | string;
    lastStatus: null | number;
    lastMessage: null | string;
  }

  /** 同步状态响应 */
  export interface SyncStatus {
    types: TypeStatus[];
  }

  /** 同步日志行 */
  export interface SyncLog {
    id: string;
    dataType: string;
    triggerType: number;
    status: number;
    rowsTotal: null | number;
    rowsInserted: null | number;
    rowsUpdated: null | number;
    rowsDisabled: null | number;
    message: null | string;
    startedAt: string;
    finishedAt: null | string;
  }
}

// ==================== 行政区划 ====================

/** 区划懒加载子节点（parentCode 为空返回省级） */
export function getRegionTreeApi(parentCode?: string) {
  return requestClient.get<BaseDataApi.RegionNode[]>('/system/basedata/region/tree', {
    params: { parentCode },
  });
}

/** 区划分页检索 */
export function getRegionPageApi(params: {
  keyword?: string;
  level?: number;
  status?: number;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<BaseDataApi.PageResult<BaseDataApi.RegionRow>>(
    '/system/basedata/region/page',
    { params },
  );
}

// ==================== 手机号段 ====================

/** 号段分页 */
export function getPhoneSegmentPageApi(params: {
  operator?: string;
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<BaseDataApi.PageResult<BaseDataApi.PhoneSegment>>(
    '/system/basedata/phone-segment/page',
    { params },
  );
}

/** 新增号段 */
export function createPhoneSegmentApi(data: BaseDataApi.PhoneSegmentBody) {
  return requestClient.post<void>('/system/basedata/phone-segment', data);
}

/** 编辑号段 */
export function updatePhoneSegmentApi(data: BaseDataApi.PhoneSegmentBody) {
  return requestClient.put<void>('/system/basedata/phone-segment', data);
}

/** 删除号段 */
export function deletePhoneSegmentApi(id: string) {
  return requestClient.delete<void>(`/system/basedata/phone-segment/${id}`);
}

// ==================== 银行卡 BIN ====================

/** BIN 分页 */
export function getBankBinPageApi(params: {
  bankName?: string;
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<BaseDataApi.PageResult<BaseDataApi.BankBin>>(
    '/system/basedata/bank-bin/page',
    { params },
  );
}

/** 新增 BIN */
export function createBankBinApi(data: BaseDataApi.BankBinBody) {
  return requestClient.post<void>('/system/basedata/bank-bin', data);
}

/** 编辑 BIN */
export function updateBankBinApi(data: BaseDataApi.BankBinBody) {
  return requestClient.put<void>('/system/basedata/bank-bin', data);
}

/** 删除 BIN */
export function deleteBankBinApi(id: string) {
  return requestClient.delete<void>(`/system/basedata/bank-bin/${id}`);
}

// ==================== 同步 ====================

/** 各类型同步状态 + 行数（状态卡轮询） */
export function getBasedataSyncStatusApi() {
  return requestClient.get<BaseDataApi.SyncStatus>('/system/basedata/sync/status');
}

/** 同步日志分页 */
export function getBasedataSyncLogApi(params: {
  dataType?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return requestClient.get<BaseDataApi.PageResult<BaseDataApi.SyncLog>>(
    '/system/basedata/sync/log',
    { params },
  );
}

/** 手动触发同步（异步执行，立即返回） */
export function triggerBasedataSyncApi(type: 'bin' | 'phone' | 'region') {
  return requestClient.post<void>(`/system/basedata/sync/${type}`);
}
