import type { UserInfo } from '@vben/types';

import { requestClient } from '#/api/request';

/** /user/info 返回：Vben 约定字段 + 本次新增的性别与头像原始值 */
export interface ProfileUserInfo extends UserInfo {
  /** 头像入库原始值：null / preset:N / data:image/...;base64,...（选图器回显用） */
  avatarRaw?: null | string;
  /** 性别 0未知 1男 2女 */
  gender?: number;
}

/**
 * 获取用户信息
 */
export async function getUserInfoApi() {
  return requestClient.get<ProfileUserInfo>('/user/info');
}

/**
 * 更新当前用户基本资料（个人中心）
 */
export async function updateUserProfileApi(data: {
  realName?: string;
  email?: string;
  phone?: string;
  /** 性别 0未知 1男 2女 */
  gender?: number;
  /** null=不修改；''=恢复默认；preset:N / data:image/...;base64,...=设定 */
  avatar?: null | string;
  desc?: string;
}) {
  return requestClient.put('/user/profile', data);
}

/**
 * 修改当前用户密码
 */
export async function changePasswordApi(data: {
  oldPassword: string;
  newPassword: string;
}) {
  return requestClient.put('/user/password', data);
}
