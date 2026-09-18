import { requestClient } from '#/api/request';

export namespace ConfigApi {
  export interface SysConfig {
    id?: string;
    configName: string;
    configKey: string;
    configValue: string;
    /** Y 内置不可删 / N 用户 */
    configType?: string;
    remark?: string;
  }
}

/** 参数配置分页 */
export async function getConfigPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return requestClient.get('/system/config/page', { params });
}

/** 新增参数 */
export async function createConfigApi(data: ConfigApi.SysConfig) {
  return requestClient.post('/system/config', data);
}

/** 编辑参数 */
export async function updateConfigApi(data: ConfigApi.SysConfig) {
  return requestClient.put('/system/config', data);
}

/** 删除参数 */
export async function deleteConfigApi(id: string) {
  return requestClient.delete(`/system/config/${id}`);
}
