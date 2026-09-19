import { requestClient } from '#/api/request';

/**
 * 用户偏好设置（按用户维度存储于 MongoDB）
 */
export interface UserPreference {
  /** Vben 自定义扩展偏好（可空） */
  custom?: Record<string, any>;
  /** Vben preferences 全量对象 */
  preferences: Record<string, any>;
}

/**
 * 获取当前用户偏好设置，未存储过时返回 null
 */
export async function getUserPreferenceApi() {
  return requestClient.get<null | UserPreference>('/user/preference');
}

/**
 * 保存当前用户偏好设置（全量覆盖）
 */
export async function saveUserPreferenceApi(data: UserPreference) {
  return requestClient.put('/user/preference', data);
}
