import { requestClient } from '#/api/request';

export namespace UserApi {
  export interface SysUser {
    id: string;
    username: string;
    nickname: string;
    email?: string;
    phone?: string;
    avatar?: string;
    status: number;
    lastLoginAt?: string;
    lastLoginIp?: string;
    createdAt: string;
    updatedAt: string;
  }

  export interface UserBody {
    username: string;
    nickname: string;
    password?: string;
    email?: string;
    phone?: string;
    status?: number;
    roleIds?: string[];
  }
}

/** 分页查询用户 */
export async function getUserPageApi(params: {
  pageNum?: number;
  pageSize?: number;
  username?: string;
  status?: number;
}) {
  return requestClient.get('/system/user/page', { params });
}

/** 用户详情 */
export async function getUserApi(id: string) {
  return requestClient.get<UserApi.SysUser>(`/system/user/${id}`);
}

/** 用户已绑定角色 */
export async function getUserRoleIdsApi(id: string) {
  return requestClient.get<string[]>(`/system/user/${id}/role-ids`);
}

/** 新增用户 */
export async function createUserApi(data: UserApi.UserBody) {
  return requestClient.post('/system/user', data);
}

/** 编辑用户 */
export async function updateUserApi(id: string, data: UserApi.UserBody) {
  return requestClient.put(`/system/user/${id}`, data);
}

/** 删除用户 */
export async function deleteUserApi(id: string) {
  return requestClient.delete(`/system/user/${id}`);
}
