package com.serverpanel.system.config;

import org.bson.Document;

/**
 * 用户偏好设置默认配置（后端写死）。
 *
 * <p>默认配置与前端应用级生效默认值保持一致，来源：
 * {@code packages/@core/preferences/src/config.ts} 的 {@code defaultPreferences}
 * 合并 {@code apps/web-antd/src/preferences.ts} 的 {@code overridesPreferences}
 * （{@code app.accessMode=backend}、{@code app.defaultHomePath=/dashboard/index}、
 * {@code app.name=ServerPanel}），以及自定义扩展 4 字段默认值。
 *
 * <p>用户从未配置过偏好时，{@code GET /user/preference} 返回本默认配置；
 * 后续前端默认值变更时需同步更新本类。
 */
public final class DefaultPreferenceConfig {

    /** 主偏好默认配置（13 组全量 JSON） */
    private static final Document DEFAULT_PREFERENCES = Document.parse("""
        {
          "app": {
            "accessMode": "backend",
            "authPageLayout": "panel-right",
            "checkUpdatesInterval": 1,
            "colorGrayMode": false,
            "colorWeakMode": false,
            "compact": false,
            "contentCompact": "wide",
            "contentCompactWidth": 1200,
            "contentPadding": 0,
            "contentPaddingBottom": 0,
            "contentPaddingLeft": 0,
            "contentPaddingRight": 0,
            "contentPaddingTop": 0,
            "defaultAvatar": "https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v1.webp",
            "defaultHomePath": "/dashboard/index",
            "dynamicTitle": true,
            "enableCheckUpdates": true,
            "enableCopyPreferences": true,
            "enablePreferences": true,
            "enableRefreshToken": false,
            "enableStickyPreferencesNavigationBar": true,
            "isMobile": false,
            "layout": "sidebar-nav",
            "locale": "zh-CN",
            "loginExpiredMode": "page",
            "name": "ServerPanel",
            "preferencesButtonPosition": "auto",
            "timezone": "Asia/Shanghai",
            "watermark": false,
            "watermarkContent": "",
            "zIndex": 200
          },
          "breadcrumb": {
            "enable": true,
            "hideOnlyOne": false,
            "showHome": false,
            "showIcon": true,
            "styleType": "normal"
          },
          "copyright": {
            "companyName": "Vben",
            "companySiteLink": "https://www.vben.pro",
            "date": "2024",
            "enable": true,
            "icp": "闽ICP备19024351号",
            "icpLink": "https://beian.miit.gov.cn/",
            "settingShow": true
          },
          "footer": {
            "enable": false,
            "fixed": false,
            "height": 32
          },
          "header": {
            "enable": true,
            "height": 50,
            "hidden": false,
            "menuAlign": "start",
            "mode": "fixed"
          },
          "logo": {
            "enable": true,
            "fit": "contain",
            "source": "https://unpkg.com/@vbenjs/static-source@0.1.7/source/logo-v1.webp",
            "showText": true,
            "logoMode": "icon"
          },
          "navigation": {
            "accordion": true,
            "split": true,
            "styleType": "rounded"
          },
          "shortcutKeys": {
            "enable": true,
            "globalEscape": false,
            "globalLockScreen": true,
            "globalLogout": true,
            "globalPreferences": true,
            "globalSearch": true
          },
          "sidebar": {
            "autoActivateChild": false,
            "collapsed": false,
            "collapsedButton": true,
            "collapsedShowTitle": false,
            "collapseWidth": 60,
            "draggable": true,
            "enable": true,
            "expandOnHover": true,
            "extraCollapse": false,
            "extraCollapsedWidth": 60,
            "fixedButton": true,
            "hidden": false,
            "mixedWidth": 80,
            "width": 224
          },
          "tabbar": {
            "draggable": true,
            "enable": true,
            "height": 38,
            "keepAlive": true,
            "maxCount": 0,
            "middleClickToClose": false,
            "persist": true,
            "showIcon": true,
            "showMaximize": true,
            "showMore": true,
            "showRefresh": true,
            "styleType": "chrome",
            "visitHistory": true,
            "wheelable": true
          },
          "theme": {
            "builtinType": "default",
            "colorDestructive": "hsl(348 100% 61%)",
            "colorPrimary": "hsl(212 100% 45%)",
            "colorSuccess": "hsl(144 57% 58%)",
            "colorWarning": "hsl(42 84% 61%)",
            "mode": "dark",
            "radius": "0.5",
            "fontSize": 16,
            "semiDarkHeader": false,
            "semiDarkSidebar": false,
            "semiDarkSidebarSub": false
          },
          "transition": {
            "enable": true,
            "loading": true,
            "name": "fade-slide",
            "progress": true
          },
          "widget": {
            "fullscreen": true,
            "fullscreenButtonPosition": "header",
            "globalSearch": true,
            "globalSearchButtonPosition": "header",
            "languageToggle": true,
            "languageToggleButtonPosition": "header",
            "lockScreen": true,
            "lockScreenButtonPosition": "header",
            "logoutButtonPosition": "header",
            "notification": true,
            "notificationButtonPosition": "header",
            "refresh": true,
            "refreshButtonPosition": "header",
            "sidebarToggle": true,
            "themeToggle": true,
            "themeToggleButtonPosition": "header",
            "timezone": true,
            "timezoneButtonPosition": "header",
            "order": [
              "globalSearch",
              "preferences",
              "themeToggle",
              "languageToggle",
              "timezone",
              "fullscreen",
              "refresh",
              "notification",
              "lockScreenBtn",
              "logoutBtn"
            ]
          }
        }
        """);

    /** 自定义扩展偏好默认配置（web-antd preferencesExtension 4 字段默认值） */
    private static final Document DEFAULT_CUSTOM = Document.parse("""
        {
          "enableFormFullscreen": true,
          "tenantMode": "single",
          "defaultTableSize": 20,
          "reportTitle": ""
        }
        """);

    private DefaultPreferenceConfig() {
    }

    /**
     * 获取主偏好默认配置（深拷贝，避免调用方修改常量）
     */
    public static Document getDefaultPreferences() {
        return Document.parse(DEFAULT_PREFERENCES.toJson());
    }

    /**
     * 获取自定义扩展偏好默认配置（深拷贝，避免调用方修改常量）
     */
    public static Document getDefaultCustom() {
        return Document.parse(DEFAULT_CUSTOM.toJson());
    }
}
