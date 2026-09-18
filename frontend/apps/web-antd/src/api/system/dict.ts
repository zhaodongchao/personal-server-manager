import { requestClient } from '#/api/request';

export namespace DictApi {
  export interface DictType {
    id?: string;
    dictName: string;
    dictType: string;
    status: number;
    remark?: string;
    createdAt?: string;
    updatedAt?: string;
  }

  export interface DictData {
    id?: string;
    dictType: string;
    dictLabel: string;
    dictValue: string;
    sort: number;
    status: number;
    isDefault: number;
    remark?: string;
  }
}

/** 字典类型分页 */
export async function getDictTypePageApi(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return requestClient.get('/system/dict/type/page', { params });
}

/** 新增字典类型 */
export async function createDictTypeApi(data: DictApi.DictType) {
  return requestClient.post('/system/dict/type', data);
}

/** 编辑字典类型 */
export async function updateDictTypeApi(data: DictApi.DictType) {
  return requestClient.put('/system/dict/type', data);
}

/** 删除字典类型 */
export async function deleteDictTypeApi(id: string) {
  return requestClient.delete(`/system/dict/type/${id}`);
}

/** 字典数据分页 */
export async function getDictDataPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  dictType?: string;
}) {
  return requestClient.get('/system/dict/data/page', { params });
}

/** 按类型取启用字典（下拉数据源） */
export async function getDictDataByTypeApi(dictType: string) {
  return requestClient.get<DictApi.DictData[]>(
    `/system/dict/data/type/${dictType}`,
  );
}

/** 新增字典数据 */
export async function createDictDataApi(data: DictApi.DictData) {
  return requestClient.post('/system/dict/data', data);
}

/** 编辑字典数据 */
export async function updateDictDataApi(data: DictApi.DictData) {
  return requestClient.put('/system/dict/data', data);
}

/** 删除字典数据 */
export async function deleteDictDataApi(id: string) {
  return requestClient.delete(`/system/dict/data/${id}`);
}
