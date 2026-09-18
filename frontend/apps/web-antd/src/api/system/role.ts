import { requestClient } from '#/api/request';

export namespace RoleApi {
  export interface SysRole {
    id: string;
    roleName: string;
    roleKey: string;
    sort: number;
    status: number;
    remark?: string;
    createdAt: string;
    updatedAt: string;
  }

  export interface RoleBody {
    roleName: string;
    roleKey: string;
    sort?: number;
    status?: number;
    remark?: string;
    menuIds?: string[];
  }
}

/** 分页查询角色 */
export async function getRolePageApi(params: {
  pageNum?: number;
  pageSize?: number;
  roleName?: string;
}) {
  return requestClient.get('/system/role/page', { params });
}

/** 启用角色列表（选择器） */
export async function getRoleAllApi() {
  return requestClient.get<RoleApi.SysRole[]>('/system/role/all');
}

/** 角色已授权菜单 */
export async function getRoleMenuIdsApi(id: string) {
  return requestClient.get<string[]>(`/system/role/${id}/menu-ids`);
}

/** 新增角色 */
export async function createRoleApi(data: RoleApi.RoleBody) {
  return requestClient.post('/system/role', data);
}

/** 编辑角色 */
export async function updateRoleApi(id: string, data: RoleApi.RoleBody) {
  return requestClient.put(`/system/role/${id}`, data);
}

/** 删除角色 */
export async function deleteRoleApi(id: string) {
  return requestClient.delete(`/system/role/${id}`);
}
