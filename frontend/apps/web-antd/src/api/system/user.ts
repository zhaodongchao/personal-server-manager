import { requestClient } from '#/api/request';

export namespace UserApi {
  /** 用户列表项：刻意不含头像（头像为 base64 大字段，列表接口不返回） */
  export interface SysUser {
    id: string;
    username: string;
    nickname: string;
    gender?: number;
    email?: string;
    phone?: string;
    status: number;
    lastLoginAt?: string;
    lastLoginIp?: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 用户详情：在列表字段基础上补充头像，供编辑弹窗回显 */
  export interface SysUserDetail extends SysUser {
    /** 原始值：null / preset:N / data:image/...;base64,... */
    avatar?: null | string;
    /** 归一化后的可渲染 src */
    avatarUrl?: string;
  }

  export interface UserBody {
    username: string;
    nickname: string;
    password?: string;
    email?: string;
    phone?: string;
    /** 性别 0未知 1男 2女 */
    gender?: number;
    /** null=不修改；''=恢复默认；preset:N / data:image/...;base64,...=设定 */
    avatar?: null | string;
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
  return requestClient.get<UserApi.SysUserDetail>(`/system/user/${id}`);
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
