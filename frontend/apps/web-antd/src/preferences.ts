import {
  appCopyrightPreferences,
  defineOverridesPreferences,
  definePreferencesExtension,
  getPreferences,
  updatePreferences,
} from '@vben/preferences';

interface WebAntdPreferencesExtension {
  defaultTableSize: number;
  enableFormFullscreen: boolean;
  reportTitle: string;
  tenantMode: 'multi' | 'single';
}

/**
 * @description 项目配置文件
 * 只需要覆盖项目中的一部分配置，不需要的配置不用覆盖，会自动使用默认配置
 * !!! 更改配置后请清空缓存，否则可能不生效
 */
export const overridesPreferences = defineOverridesPreferences({
  // overrides
  app: {
    accessMode: 'backend',
    // 站内默认头像。Vben 内置默认值指向 unpkg.com，该域名在生产网络不可达，
    // 会让 <img> 一直挂起到 TCP 超时并阻塞页面的 load 事件，故改为本地资源。
    defaultAvatar: '/avatar.svg',
    defaultHomePath: '/overview/monitor',
    enableRefreshToken: false,
    name: import.meta.env.VITE_APP_TITLE,
  },
  copyright: appCopyrightPreferences,
  // 站点 logo 同样改走站内资源（原默认值为 https://unpkg.com/...）；
  // 尺寸与默认保持一致的方形比例，fit/logoMode 沿用内置配置。
  logo: {
    source: '/logo.svg',
  },
});

export const preferencesExtension =
  definePreferencesExtension<WebAntdPreferencesExtension>({
    tabLabel: 'preferences.antd.tabLabel',
    title: 'preferences.antd.title',
    fields: [
      {
        component: 'switch',
        defaultValue: true,
        key: 'enableFormFullscreen',
        label: 'preferences.antd.fields.enableFormFullscreen.label',
        tip: 'preferences.antd.fields.enableFormFullscreen.tip',
      },
      {
        component: 'select',
        defaultValue: 'single',
        key: 'tenantMode',
        label: 'preferences.antd.fields.tenantMode.label',
        options: [
          {
            label: 'preferences.antd.fields.tenantMode.options.single.label',
            value: 'single',
          },
          {
            label: 'preferences.antd.fields.tenantMode.options.multi.label',
            value: 'multi',
          },
        ],
      },
      {
        component: 'number',
        componentProps: {
          max: 200,
          min: 10,
          step: 10,
        },
        defaultValue: 20,
        key: 'defaultTableSize',
        label: 'preferences.antd.fields.defaultTableSize.label',
      },
      {
        component: 'input',
        defaultValue: '',
        key: 'reportTitle',
        label: 'preferences.antd.fields.reportTitle.label',
        placeholder: 'preferences.antd.fields.reportTitle.placeholder',
      },
    ],
  });

/** 站内 logo 与默认头像（替代 Vben 内置的 unpkg.com 默认值） */
export const LOCAL_AVATAR = '/avatar.svg';
export const LOCAL_LOGO = '/logo.svg';

/** 旧版本默认静态资源所在的外部 CDN（生产环境不可达，必须纠正掉） */
const LEGACY_EXTERNAL_ASSET = /^https?:\/\/(?:cdn\.jsdelivr\.net|unpkg\.com)\//i;

/** 偏好中可能指向外部 CDN 的两个字段所在的分支 */
interface ExternalAssetBranches {
  app?: { defaultAvatar?: unknown } | null;
  logo?: { source?: unknown } | null;
}

/** 资源纠正补丁：只需给出需要改写的分支，未涉及字段由深合并保留 */
export interface LegacyAssetPatch {
  app?: { defaultAvatar: string };
  logo?: { source: string };
}

/** 判断某个资源地址是否为历史遗留的外部 CDN 地址 */
export function isLegacyExternalAsset(url: unknown): boolean {
  return typeof url === 'string' && LEGACY_EXTERNAL_ASSET.test(url);
}

/**
 * 计算「把外部 CDN 资源替换为站内资源」所需的最小补丁。
 *
 * 抽成纯函数是为了让两条入口共用同一套判定：
 * ① `initPreferences` 之后纠正 localStorage 缓存（{@link fixLegacyExternalAssets}）；
 * ② 登录后拉取云端偏好时纠正服务端持久化的历史数据
 *    （`utils/preference-sync.ts` 的 `initPreferenceSync`）。
 * 否则云端那份旧值会在登录后把本地已纠正的值再覆盖回去。
 *
 * 返回的补丁可直接交给 `updatePreferences`——它是深合并，未涉及的字段不会丢失。
 *
 * @param source 待检查的偏好（本地缓存或服务端返回的偏好均可）
 * @returns 最小补丁；无需纠正时返回 null
 */
export function legacyAssetPatch(
  source: ExternalAssetBranches | null | undefined,
): LegacyAssetPatch | null {
  const patch: LegacyAssetPatch = {};

  if (isLegacyExternalAsset(source?.logo?.source)) {
    patch.logo = { source: LOCAL_LOGO };
  }
  if (isLegacyExternalAsset(source?.app?.defaultAvatar)) {
    patch.app = { defaultAvatar: LOCAL_AVATAR };
  }

  return patch.logo || patch.app ? patch : null;
}

/**
 * 纠正当前偏好设置中指向外部 CDN 的 logo 与默认头像（本地缓存入口）。
 *
 * Vben 的 `initPreferences` 采用「缓存优先」合并：localStorage 里由旧版本写入的
 * `logo.source` / `app.defaultAvatar` 会盖住新的站内默认值，导致老用户浏览器
 * 仍然去请求 unpkg.com。该域名在生产环境（内网、企业代理）不可达，`<img>` 会
 * 一直挂起到 TCP 超时，并阻塞页面的 load 事件（实测 nav.load ≈ 19.8s）。
 *
 * 因此这里在初始化完成后显式纠正一次并回写缓存，老用户无需清缓存即可生效。
 * 只在被外部地址污染时才写入，用户自定义的 logo 不会被覆盖。
 *
 * @returns 是否发生了纠正
 */
export function fixLegacyExternalAssets(): boolean {
  const patch = legacyAssetPatch(getPreferences());
  if (!patch) {
    return false;
  }
  updatePreferences(patch);
  return true;
}
