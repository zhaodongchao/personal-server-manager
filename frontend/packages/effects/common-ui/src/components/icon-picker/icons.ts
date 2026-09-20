import type { Recordable } from '@vben/types';

/**
 * 一个缓存对象，在不刷新页面时，无需重复请求远程接口
 */
export const ICONS_MAP: Recordable<string[]> = {};

interface IconifyResponse {
  prefix: string;
  total: number;
  title: string;
  uncategorized?: string[];
  categories?: Recordable<string[]>;
  aliases?: Recordable<string>;
}

const PENDING_REQUESTS: Recordable<Promise<string[]>> = {};

/** 站内图标集清单缓存（多个图标选择器共享，全程只请求一次） */
let COLLECTIONS_CACHE: null | Recordable<IconifyResponse> = null;

/**
 * 读取站内图标集清单。
 *
 * 数据由 frontend/scripts/gen-iconify-offline.py 生成，形如
 * `{ [prefix]: { prefix, total, title, uncategorized } }`。
 * 取代原先对 https://api.iconify.design/collection 的外网请求：该域名在生产网络
 * 不可达，会导致每次打开图标选择器都挂起 10s 后抛错、列表为空。
 * @param signal 中止信号
 */
async function loadCollections(
  signal?: AbortSignal,
): Promise<Recordable<IconifyResponse>> {
  if (COLLECTIONS_CACHE) {
    return COLLECTIONS_CACHE;
  }
  // 注意：先赋给局部变量再回填缓存。把 `any`（res.json() 的返回类型）直接
  // 赋给可空变量会重置 TS 的控制流收窄，导致末尾 return 仍被判定为可能为 null。
  const data: Recordable<IconifyResponse> = await fetch(
    '/iconify/collections.json',
    { signal },
  ).then((res) => res.json());
  COLLECTIONS_CACHE = data;
  return data;
}

/**
 * 通过Iconify接口获取图标集数据。
 * 同一时间多个图标选择器同时请求同一个图标集时，实际上只会发起一次请求（所有请求共享同一份结果）。
 * 请求结果会被缓存，刷新页面前同一个图标集不会再次请求
 * @param prefix 图标集名称
 * @returns 图标集中包含的所有图标名称
 */
export async function fetchIconsData(prefix: string): Promise<string[]> {
  if (Reflect.has(ICONS_MAP, prefix) && ICONS_MAP[prefix]) {
    return ICONS_MAP[prefix];
  }
  if (Reflect.has(PENDING_REQUESTS, prefix) && PENDING_REQUESTS[prefix]) {
    return PENDING_REQUESTS[prefix];
  }
  PENDING_REQUESTS[prefix] = (async () => {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 1000 * 10);
      const collections = await loadCollections(controller.signal);
      clearTimeout(timeoutId);
      const response = collections[prefix];
      if (!response) {
        ICONS_MAP[prefix] = [];
        return ICONS_MAP[prefix];
      }
      // 注意：站内清单里 uncategorized 是共享引用，这里必须复制后再 push
      const list = [...(response.uncategorized || [])];
      if (response.categories) {
        for (const category in response.categories) {
          list.push(...(response.categories[category] || []));
        }
      }
      ICONS_MAP[prefix] = list.map((v) => `${prefix}:${v}`);
    } catch (error) {
      console.error(`Failed to fetch icons for prefix ${prefix}:`, error);
      return [] as string[];
    }
    return ICONS_MAP[prefix];
  })();
  return PENDING_REQUESTS[prefix];
}
