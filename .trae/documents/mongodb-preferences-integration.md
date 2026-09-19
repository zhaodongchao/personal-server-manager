# MongoDB 集成与前端偏好设置云端存储实施计划

## 一、方案概述

- **后端**：在 server-framework 引入 `spring-boot-starter-data-mongodb`，提供通用 CRUD 封装 `MongoBaseService`（保存/查询/删除/计数）与启动连通性检查；在 server-system 新增用户偏好设置文档（存 MongoDB），暴露 `GET/PUT /api/v1/user/preference` 两个接口（仅需登录）。
- **前端**：新增偏好设置云同步模块——登录后从云端拉取偏好并应用，监听偏好变更（含自定义扩展项）防抖保存到云端；localStorage 仍作为即时缓存，MongoDB 为持久层。
- **文档**：按 `documents/api-spec.md` 的既有格式补充新接口说明。

## 二、现状分析（基于源码核对）

| 关键点 | 现状 |
| --- | --- |
| 后端架构 | Spring Boot 4.1.1 / Java 21 多模块；server-framework 为基础设施层（Redis/WebSocket/MyBatis-Plus/Sa-Token 等），server-system 依赖 framework |
| 鉴权模型 | `/api/**` 默认需登录（SaTokenConfigure），用户自助接口 `/user/info`、`/user/password` 均不挂 `@SaCheckPermission` |
| 统一响应 | `R<T>`（code=0 成功）；错误码分段 1xxx~6xxx，本需求无需新增错误码 |
| MongoDB | 后端目前**无任何** mongo 依赖（已 grep 核对全部 pom） |
| 连接串 | 用户给定 `mongodb://server_panel@127.0.0.1:27017/?authSource=admin`，**无密码、无库名**；本机 27017 需 WindTerm 隧道转发（与 3306/6379 同机制） |
| 前端偏好 | `@core/preferences` 的 `PreferenceManager`：状态为响应式代理，公开 `updatePreferences` / `updateCustomPreferences` / `getCustomPreferences`，导出单例 `preferences`（readonly reactive，可 `watch`）；自定义扩展项为 web-antd 的 4 个字段（defaultTableSize/enableFormFullscreen/reportTitle/tenantMode） |
| 前端登录链路 | `router/guard.ts` 的 `setupAccessGuard` 在登录态建立后执行 `fetchUserInfo` → 生成动态路由，是云同步的挂载点 |
| 请求客户端 | `requestClient`（responseReturn: 'data'，timeout 30s），API 模块统一从 `#/api` 聚合导出 |

## 三、变更内容

### 后端（backend/）

#### 1. server-framework/pom.xml — 引入 MongoDB starter

新增依赖（版本由 spring-boot-starter-parent 4.1.1 BOM 管理，无需显式版本号）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

> 验证点：SB 4 若已更名该 starter（类同 web→webmvc），实现时以 `mvn dependency:resolve` 核实；若不存在则降级直接依赖 `spring-data-mongodb` + `mongodb-driver-sync`。

#### 2. server-boot/src/main/resources/application.yml — 新增连接配置

在 `spring:` 节点下追加（与 data.redis 平级）：

```yaml
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://server_panel@127.0.0.1:27017/server_panel?authSource=admin}
```

说明：给定 URI 无库名，默认追加 `/server_panel` 库；密码为空即按给定值使用；连接串整体可用 `MONGODB_URI` 环境变量覆盖。

#### 3. 新增 `server-framework/.../framework/mongo/MongoBaseService.java` — 通用 CRUD 封装

- `@Component`，注入 `MongoTemplate`，基于集合名 + 泛型实体提供基础方法：
  - `save(T entity, String collectionName)`：保存/覆盖
  - `findById(Object id, Class<T> clazz, String collectionName)`：按 ID 查
  - `findOne(Query, Class<T>, String)` / `find(Query, Class<T>, String)`：条件查询单条/列表
  - `count(Query, String)` / `exists(Query, Class<T>, String)`：计数/存在判断
  - `remove(Query, String)`：条件删除
  - `upsert(Query, UpdateDefinition, Class<T>, String)`：更新或插入
- Javadoc 按规范：`@author zhaodc`、`@since 2026-09-19`。

#### 4. 新增 `server-framework/.../framework/mongo/MongoConnectivityChecker.java` — 启动连通性检查

- 实现 `ApplicationRunner`：执行 `mongoTemplate.executeCommand("ping")`。
- 成功：`log.info` 连接信息；失败：`log.warn` 告警（MongoDB 不可用**不阻断**后端启动，与 Redis/MySQL 行为解耦）。

#### 5. server-system 新增用户偏好设置（首个业务使用方）

| 文件 | 内容 |
| --- | --- |
| `system/entity/mongo/UserPreferenceDocument.java` | `@Document("user_preference")`：`id`、`userId`（String，**唯一索引**）、`preferences`（`Document`，存前端偏好全量 JSON）、`updatedAt`。不继承 MyBatis 的 BaseEntity |
| `system/service/UserPreferenceService.java` | `getByUserId(String userId)`：查询返回文档或 null；`save(String userId, Document preferences)`：按 userId upsert（`@PostConstruct` 中为 `user_preference` 创建 `userId` 唯一索引）。Javadoc 规范同上 |
| `system/controller/AccountController.java` | 追加两个接口（该 Controller 已承载 `/user/info`、`/user/password`，同模式仅需登录）：`GET /api/v1/user/preference` → `R<Object>`（无记录时 data=null）；`PUT /api/v1/user/preference`，body `PreferencesBody { @NotNull Document preferences }` → `R<Void>` |
| `system/dto/auth/PreferencesBody.java` | 请求体 DTO（放 dto/auth 下与 PasswordBody 同层） |

> server-system 已依赖 server-framework，mongo 依赖经传递可用，pom 无需改动。

#### 6. documents/api-spec.md — 补充接口文档

- 「二、认证与账户」新增小节「8. 偏好设置」：GET/PUT `/api/v1/user/preference`，说明按用户维度存储、无需权限码、仅登录。
- 「八、前端接入要点」补充：登录后拉取云端偏好 + 变更自动保存的时序说明。

### 前端（frontend/apps/web-antd/）

#### 7. 新增 `src/api/preference.ts` — 偏好接口封装

```ts
export async function getUserPreferenceApi() {
  return requestClient.get<null | { preferences: Record<string, any> }>(
    '/user/preference',
  );
}

export async function saveUserPreferenceApi(
  preferences: Record<string, any>,
) {
  return requestClient.put('/user/preference', { preferences });
}
```

并在 `src/api/index.ts` 追加 `export * from './preference';`。

#### 8. 新增 `src/utils/preference-sync.ts` — 云同步模块（核心）

逻辑：

1. 模块级幂等标志，保证仅初始化一次。
2. `initPreferenceSync()`：
   - `getUserPreferenceApi()` 拉取云端偏好；成功且有数据时 `updatePreferences(remote.preferences)` 应用（异常静默捕获，不阻塞启动）；
   - 记录当前快照 JSON（`JSON.stringify(preferences)`）作为基线；
   - `watch(preferences, deep)` 与 `watch(getCustomPreferences(), deep)` → 防抖（`useDebounceFn` 800ms）触发保存。
3. 保存前对比快照：与上次已同步内容一致则跳过（避免远端回显写回的死循环）；不一致则 `saveUserPreferenceApi(全量 preferences)` 并更新基线。
4. 失败静默（控制台 warn），localStorage 兜底。

#### 9. `src/router/guard.ts` — 挂载同步入口

在 `setupAccessGuard` 中 `fetchUserInfo` 之后插入：

```ts
const userInfo = userStore.userInfo || (await authStore.fetchUserInfo());
// 登录态建立后异步同步云端偏好设置（不 await：不阻塞路由首屏，本地缓存先行渲染）
initPreferenceSync();
```

不侵入 `@core/preferences` 包，零框架改动。

## 四、假设与决策

| 决策 | 理由 |
| --- | --- |
| 连接串默认按用户给定值，库名补为 `server_panel`，`MONGODB_URI` 可整体覆盖 | 给定 URI 缺库名；生产/开发差异走环境变量，与现有 `MYSQL_HOST` 等风格一致 |
| 偏好**全量 JSON** 存单文档（不按字段拆分） | 与前端 preferences 结构天然一致；升级兼容靠前端 merge 默认值的既有机制 |
| 偏好数据含运行时字段（如 `app.isMobile`）一并存储 | Vben 本地缓存同样全量存储，行为一致，无额外过滤逻辑 |
| 新接口仅登录、不挂权限码 | 与 `/user/info` 一致，属用户自助数据 |
| 登出不清理云端数据 | 数据按用户维度，下次登录重新拉取 |
| 前端保存用 watch + 防抖 + 快照比对 | `updatePreferences` 无变更回调钩子，响应式 watch 是最小侵入方案 |
| MongoDB 连不上仅告警不阻断启动 | 偏好功能是增强项，不能拖垮面板主功能 |

## 五、验证步骤

1. **编译**：`mvn -q -T 1C package -DskipTests`（backend 目录）确认模块编译通过，同时核实 mongo starter 在 SB4 下的坐标有效性。
2. **环境检查**：`netstat -ano | findstr ":27017"` 确认 27017 隧道已建立（WindTerm 转发）；若未监听需先开通隧道再验证。
3. **后端启动**：观察 `MongoConnectivityChecker` 日志（ping 成功/失败）。
4. **接口验证**：登录拿 token → `PUT /api/v1/user/preference` 写入样例 JSON → `GET` 读回一致；Swagger UI 可见新接口。
5. **Mongo 落库**：查询 `user_preference` 集合确认文档与 `userId` 唯一索引存在（第二次 PUT 同一用户应为覆盖而非新增）。
6. **前端联调**：`pnpm run dev:antd` → admin 登录 → 修改主题模式/语言/设置面板扩展项（如 defaultTableSize）→ 等待防抖保存 → 刷新页面确认偏好从云端恢复；换浏览器/清 localStorage 后登录仍可恢复。
7. **回归**：登出再登录、另一个浏览器会话修改偏好互不影响（按用户隔离）。
