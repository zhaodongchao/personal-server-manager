import { requestClient } from '#/api/request';

export namespace QuickNavApi {
  export interface QuickNav {
    id?: string;
    /** 展示名 */
    displayName: string;
    /** 访问域名/主机，留空则用面板当前访问域名 */
    domain?: string;
    /** 1 https 0 http */
    https?: number;
    /** Web 端口（-1 未知） */
    port?: number;
    /** Web 路径前缀 */
    path?: string;
    /** 前端图标 */
    icon?: string;
    /** 排序（小在前） */
    sort?: number;
    /** 1 启用 0 停用 */
    status?: number;
    remark?: string;
  }
}

/** 快捷导航分页 */
export async function getQuickNavPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return requestClient.get('/system/quick-nav/page', { params });
}

/** 新增快捷导航 */
export async function createQuickNavApi(data: QuickNavApi.QuickNav) {
  return requestClient.post('/system/quick-nav', data);
}

/** 编辑快捷导航 */
export async function updateQuickNavApi(data: QuickNavApi.QuickNav) {
  return requestClient.put('/system/quick-nav', data);
}

/** 删除快捷导航 */
export async function deleteQuickNavApi(id: string) {
  return requestClient.delete(`/system/quick-nav/${id}`);
}
