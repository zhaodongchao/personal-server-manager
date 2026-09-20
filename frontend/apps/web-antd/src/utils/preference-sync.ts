/**
 * 偏好设置云同步：将 Vben 偏好设置（含自定义扩展项）持久化到 MongoDB。
 *
 * - 登录态建立后调用 {@link initPreferenceSync}：拉取云端偏好并应用
 *   （未配置过时后端返回写死的默认配置，恒非空）；
 * - 通过 watch 响应式状态捕获面板变更，防抖后全量保存（快照比对避免远端回显写回）；
 * - localStorage 仍由 Vben PreferenceManager 维护，作为即时缓存，MongoDB 为持久层。
 */
import { watch } from 'vue';

import {
  getCustomPreferences,
  preferences,
  updateCustomPreferences,
  updatePreferences,
} from '@vben/preferences';
import { useDebounceFn } from '@vueuse/core';

import { getUserPreferenceApi, saveUserPreferenceApi } from '#/api';
import type { UserPreference } from '#/api/preference';

/** 防抖间隔（毫秒） */
const SAVE_DEBOUNCE_MS = 800;

/** 是否已初始化（保证仅同步一次） */
let initialized = false;

/** 最近一次已同步内容的 JSON 快照（基线），用于跳过回显与无变化保存 */
let baseline = '';

function currentSnapshotJson(): string {
  return JSON.stringify({
    custom: { ...getCustomPreferences() },
    preferences,
  });
}

function currentSnapshot(): UserPreference {
  return JSON.parse(currentSnapshotJson());
}

const debouncedSave = useDebounceFn(async () => {
  const json = currentSnapshotJson();
  if (json === baseline) {
    return;
  }
  baseline = json;
  try {
    await saveUserPreferenceApi(currentSnapshot());
  } catch (error) {
    console.warn('[preference-sync] save preferences failed:', error);
  }
}, SAVE_DEBOUNCE_MS);

/**
 * 立即保存当前完整偏好配置（供偏好设置抽屉手动「保存」按钮调用）。
 * 失败时向上抛出，由调用方负责结果提示。
 */
export async function savePreferencesNow(): Promise<void> {
  await saveUserPreferenceApi(currentSnapshot());
}

/**
 * 初始化偏好设置云同步（幂等）
 *
 * 拉取云端偏好 → 应用 → 建立基线 → 监听变更防抖保存。
 * 任何一步失败都不影响应用启动，仅降级为本地缓存。
 */
export async function initPreferenceSync() {
  if (initialized) {
    return;
  }
  initialized = true;

  try {
    const remote = await getUserPreferenceApi();
    if (remote?.preferences) {
      updatePreferences(remote.preferences);
    }
    if (remote?.custom) {
      updateCustomPreferences(remote.custom);
    }
  } catch (error) {
    console.warn('[preference-sync] load remote preferences failed:', error);
  }

  // 远端数据（或本地兜底数据）应用完毕后建立基线，避免回显触发保存
  baseline = currentSnapshotJson();

  // 主偏好与自定义扩展偏好均为响应式代理，深度监听变更
  watch(preferences, () => debouncedSave(), { deep: true });
  watch(getCustomPreferences(), () => debouncedSave(), { deep: true });
}
