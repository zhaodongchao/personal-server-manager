# AGENTS.md — ServerPanel 项目认知档案

> 本文档为项目全局认知档案，帮助 AI 快速掌握项目基础架构、技术栈与整体结构。**仅记录概要信息，不包含编码规则**（开发强制规则见 `.trae/rules/project_rules.md`）。

## 1. Project Overview（项目概览）

- **项目定位**：ServerPanel 个人服务器管理系统，为单机 Linux 服务器提供可视化管理能力，属于 DevOps 面板类业务。核心能力覆盖：系统用户/角色/菜单/字典等基础权限、服务器监控（CPU/内存/磁盘/进程）、文件管理、Docker 容器与镜像管理、Nginx 网站托管、MySQL 数据库管理、计划任务、防火墙规则管理。
- **架构模式**：前后端分离架构。
  - 后端：**单体多模块 Maven 工程**（`backend/`），按业务域拆分为独立 Maven module，由 `server-boot` 统一装配启动，不属于微服务架构。
  - 分层：严格遵循 `Controller → Service → Mapper/外部能力 → Database` 单向分层调用链；框架能力（认证、异常、MyBatis-Plus、Redis、MongoDB 基类）沉淀于 `server-framework`；通用工具与公共模型沉淀于 `server-common`。
- **前端能力**：基于 Vben Admin 5.x 的 pnpm Monorepo，交付单页应用（`frontend/apps/web-antd`，Ant Design Vue 版），输出为静态 `dist` 资源；另有 `apps/backend-mock`（Nitro 本地 Mock）与 `apps/docs`（VitePress 文档）。
- **接口对接**：后端 REST 接口统一前缀 `/api/v1`，返回统一响应体 `R<T>`；采用 Sa-Token Bearer Token 认证（请求头 `Authorization: Bearer <token>`）；实时监控通过 WebSocket `/ws` 推送；接口文档由 springdoc OpenAPI 生成（`/swagger-ui.html`）。前端开发环境通过 Vite 代理将 `/api`、`/ws` 转发至后端 `localhost:8080`。

## 2. Tech Stack & Versions（技术栈与版本）

### 后端技术栈（`backend/`）

| 分类 | 组件 | 版本 |
| --- | --- | --- |
| 基础环境 | JDK | 21（`java.version` / `maven.compiler.release`） |
| 构建工具 | Maven（`spring-boot-starter-parent` 构建） | 由 Spring Boot BOM 管理 |
| 核心框架 | Spring Boot | **4.1.1** |
| Web / 校验 | `spring-boot-starter-webmvc` / `-validation` | 随 SB4 管理 |
| 持久层 ORM | MyBatis-Plus（`mybatis-plus-spring-boot4-starter`） | 3.5.17 |
| 分页插件 | mybatis-plus-jsqlparser | 3.5.17 |
| 数据库连接池 | Druid（`druid-spring-boot-4-starter`） | 1.2.28 |
| 数据库 | MySQL（`mysql-connector-j`） | 由 SB4 BOM 管理 |
| 缓存 | Redis（Lettuce 连接池） | 由 SB4 BOM 管理 |
| 文档型存储 | MongoDB（`spring-boot-starter-data-mongodb`） | 由 SB4 BOM 管理 |
| 认证授权 | Sa-Token（`sa-token-spring-boot4-starter`） | 1.46.0 |
| 密码哈希 | `spring-security-crypto`（仅 BCrypt） | 由 SB4 BOM 管理 |
| 数据库迁移 | Flyway（`spring-boot-starter-flyway` + `flyway-mysql`） | 由 SB4 BOM 管理 |
| 实时推送 | `spring-boot-starter-websocket` | 随 SB4 管理 |
| AOP 切面 | `spring-boot-starter-aspectj`（审计切面） | 随 SB4 管理 |
| 接口文档 | springdoc-openapi-starter-webmvc-ui | 3.1.1 |
| 系统指标采集 | oshi-core（OSHI） | 7.6.1 |
| Docker 客户端 | docker-java-core / transport-httpclient5 | 3.7.1 |
| 调度/表达式 | cron-utils | 9.2.1 |
| 模板引擎 | FreeMarker（Nginx 站点配置模板） | 由 SB4 BOM 管理 |
| 简化编码 | Lombok | 由 SB4 BOM 管理 |
| 代码格式化 | Spotless Maven 插件（palantirJavaFormat） | 3.10.2 |
| 线程模型 | 虚拟线程（`spring.threads.virtual.enabled=true`） | Level |

### 前端技术栈（`frontend/`，Vben Admin Monorepo）

| 分类 | 组件 | 版本 |
| --- | --- | --- |
| 基础环境 | Node.js（`.node-version`） | 24.16.0（engines：`^22.18.0 ‖ ^24.12.0`） |
| 包管理器 | pnpm（workspaces + catalog） | **11.16.0** |
| 构建编排 | Turbo | 2.10.12 |
| 核心框架 | Vue | 3.5.40 |
| 构建工具 | Vite | 8.2.2 |
| 路由 | vue-router | 5.2.0 |
| 状态管理 | Pinia（含 `pinia-plugin-persistedstate`） | 4.0.2 |
| UI 组件库 | Ant Design Vue（`web-antd` 应用） | 4.2.6 |
| 表格方案 | VXE-Table / vxe-pc-ui | 4.21.2 / 4.17.18 |
| 语言 | TypeScript | 6.0.3 |
| 类型检查 | vue-tsc | 3.3.11 |
| 请求库 | axios（经 `@vben/request` 封装） | 1.18.1 |
| 服务端状态 | @tanstack/vue-query | 5.102.3 |
| 日期工具 | dayjs | 1.11.21 |
| 富文本 | @tiptap（editor） | 3.30.3 |
| 图表 | echarts | 6.1.0 |
| 样式框架 | Tailwind CSS | 4.3.3 |
| Lint | ESLint / oxlint / stylelint / oxfmt | 10.9.1 / 1.80.0 / 17.14.1 / 0.65.0 |
| 单元测试 | Vitest + @vue/test-utils + happy-dom | 4.1.11 / 2.4.11 / 20.11.6 |
| E2E | Playwright | 1.62.1 |

## 3. Project Structure（项目目录结构）

### 项目根目录

```
/workspace
├── backend/            # 后端多模块 Maven 工程
├── frontend/           # 前端 pnpm Monorepo（Vben Admin 5.x）
├── docker/             # Docker 相关（mysql-init 等初始化脚本）
├── scripts/            # 构建 / 安装脚本（build.sh、install.sh）
├── documents/          # 项目设计文档（api-spec、部署方案、初始化 SQL 等）
├── tasks/              # 任务沉淀（经验与教训）
├── .trae/rules/        # Trae 项目级规则（强制开发规范）
├── AGENTS.md           # 本文档，项目全局认知档案
├── README.md
└── docker-compose.dev.yml
```

### 后端目录（`backend/`）

Maven 多模块，各模块均采用 `controller / service / mapper / entity / dto / config / security(或 ws/audit)` 包结构：

| 模块 | 职责 |
| --- | --- |
| `server-common` | 公共模型与工具：统一响应体 `R<T>`、`PageQuery`/`PageResult`、`BaseEntity`、`ErrorCode`、`ServiceException`、常量、`@Audit` 注解 |
| `server-framework` | 技术框架集成：Sa-Token 认证配置、全局异常处理 `GlobalExceptionHandler`、MyBatis-Plus 配置、审计切面、命令执行 `CommandExecutor`、Mongo 基类、SPA 前端转发 |
| `server-system` | 系统管理：用户/角色/菜单/字典/配置/登录鉴权/审计日志/用户偏好 |
| `server-monitor` | 服务器监控：指标采集（OSHI）、监控接口、WebSocket 实时推送 |
| `server-file` | 文件管理：文件列表/目录操作、回收站、根目录白名单安全校验 |
| `server-ops` | 运维管理：进程、服务、计划任务（cron）、防火墙 |
| `server-appstack` | 应用栈管理：Docker 容器/镜像、Nginx 网站、MySQL 数据库 |
| `server-boot` | 启动装配模块：汇总所有业务模块、Flyway 迁移、配置与打包（产物名 `serverpanel`） |

分层职责约定：`controller` 仅请求接入与参数校验；`service` 承载业务逻辑与事务；`mapper` 仅数据库交互；`dto` 承载入参（`*Body`）与出参（`*VO`）；`entity` 映射数据表；`config` 存放配置类；特殊子包如 `security`（权限）、`ws`（WebSocket）、`audit`（审计）按需拆分。

### 前端目录（`frontend/`）

pnpm + Turbo Monorepo 结构：

| 目录 | 职责 |
| --- | --- |
| `apps/web-antd` | 主应用（Ant Design Vue 版），页面与业务在此落地 |
| `apps/web-antd/src/api` | 接口请求层，按业务模块（`appstack`/`file`/`monitor`/`ops`/`system` 等）分文件管理，`request.ts` 为统一请求封装 |
| `apps/web-antd/src/layouts` | 布局（`basic.vue` 基础布局、`auth.vue` 认证布局） |
| `apps/web-antd/src/router` | 路由与导航守卫（`guard.ts` 权限守卫、`access.ts` 访问控制） |
| `apps/web-antd/src/store` | Pinia 状态（`auth.ts` 认证状态等） |
| `apps/web-antd/src/adapter` | 组件适配层（form / vxe-table 等） |
| `apps/web-antd/src/utils` | 工具与偏好同步 |
| `apps/web-antd/src/locales` | 国际化资源 |
| `apps/web-antd/src/components` / `views` | 组件与页面（按业务模块划分） |
| `apps/backend-mock` | Nitro Mock 服务（本地接口模拟，开发联调用） |
| `apps/docs` | VitePress 文档站 |
| `packages/*` | 内部共享包：`@core`（基础/UI 组件）、`effects`（access/request/hooks/layouts 等）、`constants`、`stores`、`locales`、`preferences`、`styles`、`types`、`utils`、`icons` 等 |
| `internal/*` | 工程化配置包：`lint-configs`、`vite-config`、`tailwind-config`、`tsconfig`、`node-utils`、`vsh` |
| `scripts/` | 构建与部署脚本（Docker、turbo-run、vsh） |

页面、基础组件与业务组件分层：通用基础组件（无业务语义）置于 `packages` 或 `components` 基础层；承载具体业务语义的组件置于页面所属业务模块目录，且组件与页面严格分离。

## 4. Build & Commands（构建与运行命令）

### 后端（`backend/`，Linux / Windows 通用）

```bash
# 编译
./mvnw compile             # 或 mvn compile
# 本地启动（默认 dev 环境，端口 8080）
./mvnw -pl server-boot spring-boot:run
# 单元测试
./mvnw test
# 打包（产物 serverpanel.jar）
./mvnw package
# 跳过测试打包
./mvnw package -DskipTests
# 代码格式化校验
./mvnw spotless:check
# 代码格式化应用
./mvnw spotless:apply
```

> Windows 环境将 `./mvnw` 替换为 `mvnw.cmd`。

### 前端（`frontend/`，Node ≥ 24，pnpm 11）

```bash
# 依赖安装
pnpm install
# 本地开发启动（web-antd，端口见 VITE_PORT，默认 5666）
pnpm dev:antd
# 全量开发启动（Turbo 编排）
pnpm dev
# 生产构建
pnpm build:antd
# 全量生产构建
pnpm build
# 代码检查（Lint + 类型 + 循环依赖 + 拼写）
pnpm lint / pnpm check
# 代码格式化
pnpm format
# 单元测试
pnpm test:unit
# 类型检查
pnpm check:type
```

## 5. Environment Config（环境配置说明）

### 后端配置（`backend/server-boot/src/main/resources/`）

- 多环境配置文件：`application.yml`（公共）+ `application-dev.yml` / `application-prod.yml`（环境差异化），通过 `spring.profiles.active`（默认 `dev`，可由 `SPRING_PROFILES_ACTIVE` 覆盖）加载。
- 敏感配置管理：数据库、Redis、MongoDB、面板管理 MySQL 等密码均通过环境变量注入（如 `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`MONGODB_URI`），`application*.yml` 中仅保留占位符与本地默认值，禁止明文凭据入库（当前默认值仅限本地开发）。
- 关键配置项：Druid 连接池（`spring.datasource.druid.*`）、Redis Lettuce 连接池、`spring.mongodb.uri`（SB4 起前缀为 `spring.mongodb`）、Flyway 迁移（`classpath:db/migration`）、MyBatis-Plus（`mapper-locations` / 下划线转驼峰）、Sa-Token（Token 名、超时、是否允许并发）、springdoc、虚拟线程开关。面板自有配置统一置于 `serverpanel.*`（文件白名单、登录防爆破、监控间隔、命令执行超时、Docker 宿主等）。
- 配置中心：当前未接入外部配置中心，依赖环境变量 + Profiles 机制。

### 前端配置（`frontend/apps/web-antd/`）

- 多环境变量文件：`.env` / `.env.development` / `.env.production` / `.env.analyze`。生产接口地址由 `VITE_GLOB_API_URL=/api/v1` 定义；`VITE_BASE`、`VITE_PORT`、`VITE_ROUTER_HISTORY`（production 用 hash）等通过 `VITE_` 前缀注入，仅供 Vite 读取。
- 开发代理：`vite.config.ts` 中 `server.proxy` 将 `/api` 与 `/ws` 转发至 `http://localhost:8080`（开启 `ws:true`）。
- 构建输出：Vite 默认输出至应用 `dist/` 目录；产物为纯静态资源，可由后端 `SpaForwardController` 托管，或经 `scripts/deploy` 的 Nginx/Docker 部署。
- 静态资源路径：由 `VITE_BASE` 决定资源基础路径（开发默认 `/`）。

## 6. Agent Team（智能体分工）

项目配置 7 个自定义智能体，覆盖前后端全链路职责：

| 智能体 | 核心职责 | 触发场景 | 输出边界 |
| --- | --- | --- | --- |
| **产品经理 / Pmer** | 需求拆解、业务规则定义、前后端接口契约对齐、页面与接口验收标准 | 需求评审、接口文档梳理、业务逻辑确认、交互方案对齐 | 需求/契约/验收文档，不写实现代码 |
| **架构师 / Arcter** | 前后端整体技术方案、架构评审、接口定义、技术选型、分层把控、前端路由与状态架构 | 新功能方案设计、架构重构、代码评审、组件库封装评审 | 架构/接口/技术方案，不落地业务代码 |
| **全栈开发 / Coder** | 后端业务代码、前端页面与组件、分层编码、接口开发与对接、工具类封装、前后端联调 | 功能开发、Bug 修复、代码优化 | 可运行的前后端实现代码 |
| **数据库管理员 / DBA** | 后端表结构设计、SQL 评审优化、索引、数据字典、慢 SQL；前端接口字段对齐、本地 Mock、前端数据模型 | 表结构变更、复杂 SQL、性能优化、接口对接、字段变更适配 | 建表/SQL/数据模型，聚焦数据层 |
| **测试专家 / Tester** | 后端单元测试/接口用例；前端组件测试用例、交互边界校验、回归用例 | 功能完成、代码变更、版本发布前 | 测试用例与测试代码 |
| **运维工程师 / Opser** | 后端 Docker 镜像、CI/CD 流水线、环境配置、部署排障；前端构建优化、静态资源部署、代理配置 | 部署上线、环境问题、容器化、构建性能优化 | 部署/CI/CD/构建配置 |
| **AI 集成专家 / Aler** | 前后端 AI 能力接入、Agent 工作流优化、提示词规范落地、效率工具搭建、前端组件 AI 生成规范 | 开发流程优化、AI 工具集成、规范迭代 | AI 集成方案与开发规范 |