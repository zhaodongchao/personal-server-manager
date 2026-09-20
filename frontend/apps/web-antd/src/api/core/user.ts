import type { UserInfo } from '@vben/types';

import { requestClient } from '#/api/request';

/**
 * 获取用户信息
 */
export async function getUserInfoApi() {
  return requestClient.get<UserInfo>('/user/info');
}

/**
 * 更新当前用户基本资料（个人中心）
 */
export async function updateUserProfileApi(data: {
  realName?: string;
  email?: string;
  phone?: string;
  avatar?: string;
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
