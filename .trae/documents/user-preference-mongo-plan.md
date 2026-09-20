# 用户偏好设置（MongoDB 云存储）开发计划

> 需求：读取前端偏好设置页的所有可配置项，据此生成后端的 MongoDB 配置对象；**默认配置在后端写死**——前端登录成功后从 MongoDB 加载该用户的偏好配置，**若从未配置过则返回后端写死的默认配置**；前端用户调整偏好配置后，将配置同步保存到 MongoDB，实现跨浏览器/跨设备恢复。

---

## 一、前端偏好设置可配置项清单（已从源码核对）

偏好设置分两部分：**主偏好 `preferences`**（Vben `@core/preferences` 的 13 组）与**自定义扩展 `custom`**（web-antd 应用级 4 个字段）。

### 1.1 主偏好（13 组，源自 `packages/@core/preferences/src/types.ts`）

| 组 | 可配置字段 | 值类型/枚举 |
|---|---|---|
| `app` 全局 | accessMode / authPageLayout / checkUpdatesInterval / colorGrayMode / colorWeakMode / compact / contentCompact / contentCompactWidth / contentPadding 及上下左右 / defaultAvatar / defaultHomePath / dynamicTitle / enableCheckUpdates / enableCopyPreferences / enablePreferences / enableRefreshToken / enableStickyPreferencesNavigationBar / isMobile / layout / locale / loginExpiredMode / name / preferencesButtonPosition / timezone / watermark / watermarkContent / zIndex | boolean / number / string / 枚举（LayoutType、ThemeModeType 等） |
| `breadcrumb` 面包屑 | enable / hideOnlyOne / showHome / showIcon / styleType | boolean / BreadcrumbStyleType |
| `copyright` 版权 | companyName / companySiteLink / date / enable / icp / icpLink / settingShow | string / boolean |
| `footer` 底栏 | enable / fixed / height | boolean / number |
| `header` 顶栏 | enable / height / hidden / menuAlign / mode | boolean / number / LayoutHeaderModeType |
| `logo` | enable / fit / fullLogoHeight / logoMode / showText / source / sourceDark | boolean / string / 'full'\|'icon' |
| `navigation` 导航 | accordion / split / styleType | boolean / NavigationStyleType |
| `shortcutKeys` 快捷键 | enable / globalEscape / globalLockScreen / globalLogout / globalPreferences / globalSearch | boolean |
| `sidebar` 侧边栏 | autoActivateChild / collapsed / collapsedButton / collapsedShowTitle / collapseWidth / draggable / enable / expandOnHover / extraCollapse / extraCollapsedWidth / fixedButton / hidden / mixedWidth / width | boolean / number |
| `tabbar` 标签栏 | draggable / enable / height / keepAlive / maxCount / middleClickToClose / persist / showIcon / showMaximize / showMore / showRefresh / styleType / visitHistory / wheelable | boolean / number / TabsStyleType |
| `theme` 主题 | builtinType / colorDestructive / colorPrimary / colorSuccess / colorWarning / fontSize / mode / radius / semiDarkHeader / semiDarkSidebar / semiDarkSidebarSub | string / number / ThemeModeType |
| `transition` 动画 | enable / loading / name / progress | boolean / string |
| `widget` 小部件 | fullscreen / fullscreenButtonPosition / globalSearch / globalSearchButtonPosition / languageToggle / languageToggleButtonPosition / lockScreen / lockScreenButtonPosition / logoutButtonPosition / notification / notificationButtonPosition / order / refresh / refreshButtonPosition / sidebarToggle / themeToggle / themeToggleButtonPosition / timezone / timezoneButtonPosition | boolean / string / string[] |

### 1.2 自定义扩展（4 个字段，源自 `apps/web-antd/src/preferences.ts`）

| key | 组件 | 默认值 | 约束 |
|---|---|---|---|
| `enableFormFullscreen` | switch | true | boolean |
| `tenantMode` | select | 'single' | 'single' \| 'multi' |
| `defaultTableSize` | number | 20 | 10–200，步长 10 |
| `reportTitle` | input | '' | string |

### 1.3 存储策略

偏好设置**全量 JSON 快照**存单文档，不按字段拆分：
- `preferences` → 13 组主偏好全量 JSON
- `custom` → 4 个自定义扩展字段全量 JSON
- 与前端 `localStorage` 结构天然一致；升级兼容由前端 merge 默认值的既有机制保证（未知字段被忽略，缺失字段补默认值）。

---

## 二、后端默认配置设计（写死）

### 2.1 默认配置来源

后端写死的默认配置 = **前端应用级生效默认值**，即以下三者合并结果：

1. `packages/@core/preferences/src/config.ts` 的 `defaultPreferences`（13 组默认值，如 `theme.mode='dark'`、`app.layout='sidebar-nav'`、`widget.order=[...]`）；
2. `apps/web-antd/src/preferences.ts` 的 `overridesPreferences` 覆盖项：`app.accessMode='backend'`、`app.defaultHomePath='/dashboard/index'`、`app.enableRefreshToken=false`、`app.name='ServerPanel'`（即 `VITE_APP_TITLE`）、`copyright` 应用级版权；
3. 自定义扩展 4 字段默认值：`enableFormFullscreen=true`、`tenantMode='single'`、`defaultTableSize=20`、`reportTitle=''`。

> 说明：`app.isMobile`（运行时字段，由前端断点监听更新）在默认配置中写 `false`，与前端初始值一致；`app.name` 写固定值 `ServerPanel`（对应前端 `VITE_APP_TITLE`）。

### 2.2 后端实现

新增 `server-system/.../config/DefaultPreferenceConfig.java`：

- 静态不可变 `Document DEFAULT_PREFERENCES`（13 组全量默认 JSON）与 `Document DEFAULT_CUSTOM`（4 字段默认 JSON），内容与 2.1 完全一致；
- 提供 `getDefaultPreferences()` / `getDefaultCustom()` 访问器。

**接口语义（GET）**：

```
GET /api/v1/user/preference
  命中记录 → R<{ preferences: 用户配置, custom: 用户配置|null }>
  无记录   → R<{ preferences: 后端默认配置, custom: 后端默认配置 }>   // 不再返回 null
```

即 GET 永远返回一份完整可用的配置，前端登录后直接应用，无需本地兜底默认值。

---

## 三、MongoDB 配置对象设计

### 3.1 集合与文档

集合：`user_preference`，**一人一档**（`userId` 唯一索引）。

```json
{
  "_id": "ObjectId",
  "userId": "1",                        // 唯一索引，对应 sys_user.id 的字符串
  "preferences": {                      // 主偏好全量 JSON（13 组）
    "app": { "...": "..." },
    "theme": { "mode": "light", "...": "..." }
  },
  "custom": {                           // 自定义扩展偏好（可空）
    "enableFormFullscreen": true,
    "tenantMode": "single",
    "defaultTableSize": 20,
    "reportTitle": ""
  },
  "updatedAt": "2026-09-20T10:00:00"
}
```

### 3.2 文档实体（后端）

`UserPreferenceDocument`（`@Document("user_preference")`，不继承 MyBatis BaseEntity）：
- `ObjectId id` — `@Id`
- `String userId` — `@Indexed(unique = true)`
- `Document preferences` — 主偏好全量 JSON
- `Document custom` — 自定义扩展偏好，可空
- `LocalDateTime updatedAt`

### 3.3 连接配置（application.yml）

```yaml
spring:
  mongodb:
    uri: ${MONGODB_URI:mongodb://127.0.0.1:27017/server_panel}
```

> 说明：不写死账号密码（开发分支无凭据）；生产通过 `MONGODB_URI` 环境变量注入完整连接串（含账号/认证库），与 `MYSQL_HOST` 等既有风格一致。MongoDB 不可用仅告警不阻断启动（偏好设置属增强项）。

---

## 四、后端变更清单（backend/）

| 文件 | 变更内容 |
|---|---|
| `server-framework/pom.xml` | 新增 `spring-boot-starter-data-mongodb` 依赖（SB4 BOM 管版本） |
| `server-framework/.../framework/mongo/MongoBaseService.java` | 新增：通用 CRUD 封装（save/findById/findOne/find/count/exists/remove/upsert/executeCommand），基于 `MongoTemplate` + 集合名 |
| `server-framework/.../framework/mongo/MongoConnectivityChecker.java` | 新增：`ApplicationRunner` 启动 ping；失败仅 `log.warn` 不阻断 |
| `server-system/.../config/DefaultPreferenceConfig.java` | 新增：**写死的默认配置**（见第二节），提供 `getDefaultPreferences()` / `getDefaultCustom()` |
| `server-system/.../entity/mongo/UserPreferenceDocument.java` | 新增：偏好文档实体（见 3.2） |
| `server-system/.../service/UserPreferenceService.java` | 新增：`getByUserId` / `save`（upsert）/ `deleteByUserId`；`@PostConstruct` 显式创建唯一索引（Spring Data 默认不自动建索引） |
| `server-system/.../dto/auth/PreferencesBody.java` | 新增：`{ @NotNull Document preferences; Document custom; }` |
| `server-system/.../controller/AccountController.java` | 追加两接口（仅需登录，不挂权限码，与 `/user/info` 一致）：`GET /api/v1/user/preference` → 命中返回用户配置、**未命中返回后端默认配置**；`PUT /api/v1/user/preference` → upsert 保存 |
| `server-boot/src/main/resources/application.yml` | 新增 `spring.mongodb.uri`（环境变量可覆盖） |

接口契约：

```
GET /api/v1/user/preference
  200 → R<{ preferences: Document, custom: Document|null }>
       // 用户未配置过时 preferences/custom 均为后端写死的默认配置

PUT /api/v1/user/preference   body: { preferences: Document, custom?: Document }
  200 → R<Void>               // 全量覆盖（upsert）
```

---

## 五、前端变更清单（frontend/apps/web-antd/）

| 文件 | 变更内容 |
|---|---|
| `src/api/preference.ts` | 新增：`getUserPreferenceApi()` / `saveUserPreferenceApi(data)`，类型 `UserPreference { preferences; custom? }` |
| `src/api/index.ts` | 追加 `export * from './preference';` |
| `src/utils/preference-sync.ts` | 新增云同步模块：登录后拉取云端偏好（**返回恒为非空的完整配置**，未配置过时为后端默认配置）并应用 → 建立基线快照 → `watch(preferences)` 与 `watch(getCustomPreferences())` 深度监听 → `useDebounceFn` 800ms 防抖保存全量 JSON；快照比对跳过无变化/回显写回；失败静默降级（localStorage 兜底）；模块级幂等标志 |
| `src/router/guard.ts` | `setupAccessGuard` 中 `fetchUserInfo` 后调用 `initPreferenceSync()`（不 await，不阻塞路由首屏） |

同步时序：

```
登录成功 → fetchUserInfo → initPreferenceSync()
  ├─ GET /user/preference → 返回用户配置；未配置过 → 后端默认配置（恒非空）
  │     → updatePreferences + updateCustomPreferences 应用
  └─ 建立基线 → watch 监听 → 用户调整设置 → 防抖 800ms → PUT 全量保存
登出 → 不清云端（按用户隔离，下次登录重新拉取）
```

---

## 六、落地方式与执行步骤

**落地方式**：`origin/master`（be030eb）已实现"全量 JSON 存 MongoDB + 前端云同步"基础（16 文件 / +694 行），本次在其基础上按本计划**移植并增强**：新增后端写死默认配置 `DefaultPreferenceConfig`、GET 接口改为未命中返回默认配置；application.yml 不携带 master 上的生产敏感凭据，改用环境变量占位。

执行步骤：

1. **后端**：按第四节逐文件新增/修改（含默认配置类，内容取自第二节）→ `mvn -s .mvn/settings.xml -q -T 1C compile` 验证编译。
2. **前端**：按第五节新增/修改 → `pnpm typecheck` 验证类型。
3. **文档**：`documents/api-spec.md` 补充「偏好设置」接口说明小节。
4. **自测（本机 MongoDB 可用时）**：
   - 登录 → GET `/user/preference` 首次返回**后端默认配置**（比对字段与前端默认一致）；
   - PUT 写入修改后的配置 → 再次 GET 返回用户配置（唯一索引覆盖生效）；
   - 查询 `user_preference` 集合：第二次 PUT 同一用户为覆盖而非新增；
   - 前端：改主题/语言/自定义扩展项 → 防抖保存 → 刷新/换浏览器登录后可恢复；清空该用户 Mongo 文档后登录 → 恢复后端默认配置。
5. **回归**：MongoDB 不可用时后端正常启动（warn 日志），面板其他功能不受影响。

---

## 七、风险与决策

| 决策/风险 | 结论 |
|---|---|
| 默认配置后端写死 | 与前端 `defaultPreferences` + `overrides` + 自定义扩展默认值保持一致，双端默认值同步维护（前端是唯一权威来源，后端照抄）；后续前端默认值变更时需同步更新后端常量 |
| 全量 JSON 单文档存储 | 与前端结构一致，升级兼容靠前端 merge；不做字段级拆分 |
| GET 未命中返回默认配置（恒非空） | 满足"未配置过用默认配置"需求；前端无需本地兜底默认值，逻辑更简单 |
| 偏好含运行时字段（如 `app.isMobile`）一并存储 | 与 Vben 本地缓存行为一致，不做过滤 |
| 新接口不挂权限码 | 与 `/user/info` 一致，属用户自助数据 |
| 登出不清理云端 | 数据按用户隔离，下次登录重新拉取 |
| 保存采用 watch + 防抖 + 快照比对 | `updatePreferences` 无变更回调，响应式监听是最小侵入方案 |
| MongoDB 不可用仅告警不阻断 | 偏好功能是增强项，不拖垮面板主功能 |
| 前端 `requestClient` 超时 | 沿用开发环境 SSH 隧道场景的 30s 超时（若当前分支已是 10s 则一并调整，防止偏好大 JSON 写入被中断） |
