# ServerPanel 个人服务器管理系统 — 企业级开发方案

> 类宝塔的单机服务器管理面板。后端 Java 21 + Spring Boot 4.1.1，前端 Vben Admin 5.x（Ant Design Vue），前后端同仓库（Monorepo）。

---

## 1. 项目概述

### 1.1 目标

从零构建一套部署在 Linux 服务器上、直接管理**本机**的 Web 管理面板（单机模式，类宝塔），覆盖：登录认证与 RBAC、实时系统监控、文件管理器、运维工具（进程/systemd/计划任务/防火墙）、应用栈管理（Docker/Nginx/MySQL）、操作审计。

### 1.2 已确认的关键决策（用户已选）

| 决策点 | 结论 |
|---|---|
| 管理架构 | **单机面板**：面板与被管理服务器为同一台，直接执行本地命令；架构上通过 `CommandExecutor` 抽象隔离，为未来多机（Agent/SSH）扩展预留 |
| MVP 范围 | 核心基座 + 文件管理器 + 运维工具集 + 应用栈管理（全部四组） |
| 前端 UI 库 | **Ant Design Vue**（Vben 5 的 `apps/web-antd` 模板） |
| 认证框架 | **Sa-Token**（生态与 MyBatis-Plus 契合，官方 SB4 starter 已发布；备选 Spring Security 7，本方案不采用） |

### 1.3 环境前提（开发机）

- JDK 21（LTS）、Maven 3.9+
- Node.js ≥ 20.19（以 Vben 仓库 `.nvmrc` 为准）、pnpm ≥ 10（`corepack enable`）
- Docker + Docker Compose（跑开发用 MySQL/Redis）
- 运行面板的系统：Linux x86_64/arm64，面板进程需 root（或 sudo）权限以管理 systemd/nginx/firewalld

---

## 2. 技术选型与版本清单（全部已通过 Maven Central / 官方公告核实）

### 2.1 后端

| 依赖 | 版本 | 说明 |
|---|---|---|
| Java | 21（LTS） | 启用虚拟线程 |
| Spring Boot | **4.1.1**（2026-08-20 发布） | 基于 Spring Framework 7 / Jakarta EE 11 |
| MyBatis-Plus | `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17` | **SB4 专用 starter，勿用 boot3 版**（否则启动报 `factoryBeanObjectType` 错误） |
| Druid | `com.alibaba:druid-spring-boot-4-starter:1.2.28` | SB4 专用 starter |
| Sa-Token | `cn.dev33:sa-token-spring-boot4-starter:1.46.0` + `cn.dev33:sa-token-redis-jackson:1.46.0` | 会话存 Redis；若 redis-jackson 与 SB4 的 data-redis 不兼容，则手写 `SaTokenDao`（基于 `StringRedisTemplate`，约 50 行） |
| OSHI | `com.github.oshi:oshi-core:7.6.1` | CPU/内存/磁盘/网络/进程采集，跨平台 |
| docker-java | `com.github.docker-java:docker-java-core:3.7.1` + `docker-java-transport-httpclient5:3.7.1` | 通过 Unix socket `/var/run/docker.sock` 访问 Docker Engine |
| cron-utils | `com.cronutils:cron-utils:9.2.1` | 面板内解析/计算 cron 表达式 |
| spring-security-crypto | 版本由 SB4 BOM 管理（Security 7.x） | **仅用 BCryptPasswordEncoder** 做密码哈希，不引入完整 Spring Security |
| SpringDoc | `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1` | API 文档 `/swagger-ui.html` |
| Flyway | `spring-boot-starter-flyway`（SB4 需显式引入） | 数据库版本化管理 schema |
| HikariCP | 不引入 | 用 Druid 连接池（`druid-spring-boot-4-starter` 自动装配） |

### 2.2 Spring Boot 4 适配要点（实施时必须遵守，否则编译/启动失败）

1. **starter 改名**：`spring-boot-starter-web` → `spring-boot-starter-webmvc`；`spring-boot-starter-aop` → `spring-boot-starter-aspectj`；oauth2 系列加 `security-` 前缀（本方案不用）。
2. **Jackson 3**：groupId 变为 `tools.jackson`，SB4 已默认内置并自动配置 `ObjectMapper`；自定义序列化器需用 Jackson 3 API（`tools.jackson.databind`）。`java.time` 默认可序列化。
3. 模块化拆分：第三方 starter 若声明为 `spring-boot-autoconfigure` 旧包结构会失效 —— 一律使用上文列出的 **SB4 专用 starter**（MyBatis-Plus/Druid/Sa-Token 均已有）。
4. 虚拟线程：`application.yml` 设置 `spring.threads.virtual.enabled=true`（Tomcat 请求线程、`@Scheduled`、命令执行等待均受益）。
5. Servlet 容器为 Tomcat 11（Jakarta EE 11 命名空间 `jakarta.*`）。

### 2.3 前端（Vben Admin 5.x）

- 获取方式：`git clone --depth=1 https://github.com/vbenjs/vue-vben-admin.git`，业务应用落在 `apps/web-antd`；删除 `apps/web-ele`、`apps/web-naive`、`playground` 目录，保留 `packages/@vben/*` 框架包与 `internal/*` 工程配置（pnpm monorepo + Turbo）。
- 技术栈（模板自带）：Vue 3.5+、Vite、TypeScript、Tailwind CSS v4、Pinia、Vue Router、Ant Design Vue、`@vben/plugins/echarts`（ECharts 封装）、Nitro Mock（开发早期联调，后弃用）。
- 版本以仓库 `main` 最新稳定提交为准（5.5.x 系列）。

---

## 3. 整体架构

```
┌─────────────────────────── 浏览器 ───────────────────────────┐
│  Vben Admin 5 (web-antd)  SPA                               │
│  登录/布局/动态路由/按钮级权限(v-perms)/ECharts/文件管理器UI    │
└──────────────┬────────────────────────────┬──────────────────┘
        HTTPS /api/v1/* (JSON, Sa-Token: Authorization 头)
               │                     /ws/monitor (WebSocket + token)
┌──────────────▼────────────────────────────▼──────────────────┐
│  Spring Boot 4.1.1 (server-boot, 单 jar)                      │
│  ┌────────── 统一层 ──────────┐                               │
│  │ Sa-Token 鉴权 │ 全局异常 │ R<T> 响应 │ 审计 AOP │ OpenAPI │ │
│  ├─────────────────────────────┴───────────────────────────────┤
│  │ system 模块      monitor 模块     file 模块                   │
│  │ 用户/角色/菜单   OSHI 采集→Redis  NIO+白名单根目录             │
│  │ 字典/配置/审计   环形缓存→WS 推送  上传/下载/编辑/回收站        │
│  │ ops 模块         appstack 模块                                │
│  │ ProcessHandle   docker-java / nginx conf 生成 / MySQL DDL     │
│  │ systemctl 封装   /etc/nginx/panel.d + nginx -t 校验           │
│  │ cron-utils 调度  mysqldump 备份                               │
│  ├──────────────────────────────────────────────────────────────┤
│  │ CommandExecutor（安全命令层：argv 数组、超时、白名单）         │
│  ├──────────────────────────────────────────────────────────────┤
│  │ MyBatis-Plus(SB4 starter) + Druid + Sa-Token(Redis) + Flyway  │
│  └──────────────┬──────────────────────────────┬───────────────┘
┌─────────────────▼──────────┐   ┌───────────────▼──────────────┐
│ MySQL 8.4 (业务/审计/任务)   │   │ Redis 7 (会话/监控环形缓存/防爆破)│
└────────────────────────────┘   └──────────────────────────────┘
```

分层约定：`Controller（薄）→ Service（接口 + Impl）→ Mapper（MyBatis-Plus BaseMapper）`；DTO 用 Java 21 record；实体继承统一 `BaseEntity`。

---

## 4. 仓库结构（前后端同目录）

```
/workspace（仓库根）
├── backend/                          # Maven 多模块，groupId: com.serverpanel
│   ├── pom.xml                       # 父 POM（spring-boot-starter-parent:4.1.1）
│   ├── server-common/                # R<T>、业务异常、错误码、常量、工具、BaseEntity、@Audit 注解
│   ├── server-framework/            # 技术集成：Sa-Token/MyBatis-Plus/Druid/Redis/WS/安全(CORS、路径校验)、CommandExecutor
│   ├── server-system/               # 用户/角色/菜单/字典/配置/审计日志/登录日志
│   ├── server-monitor/              # OSHI 采集、Redis 环形缓存、WebSocket 推送、历史聚合
│   ├── server-file/                 # 文件管理（NIO）、上传下载、回收站
│   ├── server-ops/                  # 进程、systemd 服务、计划任务(cron-utils)、防火墙
│   ├── server-appstack/             # Docker(docker-java)、Nginx 网站、MySQL 数据库
│   └── server-boot/                 # ServerPanelApplication 启动类、application*.yml、
│                                     #   Flyway 迁移(db/migration)、聚合打包(含前端静态资源)
├── frontend/                         # Vben Admin 5.x monorepo（见 §6）
├── docker-compose.dev.yml           # 开发环境：mysql:8.4 + redis:7-alpine
├── scripts/                          # deploy.sh(构建产物合成单 jar)、install.sh(安装为 systemd 服务)
├── .gitignore  LICENSE
```

依赖方向：`boot → 各业务模块 → framework → common`；业务模块之间不互相依赖（跨域需求通过 common 中的接口 + Spring 事件解耦）。

---

## 5. 后端详细设计

### 5.1 统一规范

- **响应**：`R<T>{ code, msg, data }`，code=0 成功；错误码分段：1xxx 通用、2xxx 认证、3xxx 系统、4xxx 文件、5xxx 运维、6xxx 应用栈。
- **异常**：`BizException(code,msg)` + `@RestControllerAdvice` 全局处理；参数校验用 `spring-boot-starter-validation`（`@Valid` + record 上的约束注解）。
- **审计**：`@Audit(module, action)` 注解 + AspectJ 环绕，落 `sys_audit_log`（入参、出参 code、耗时、IP、UA）。
- **鉴权**：Sa-Token 注解式 `@SaCheckPermission("system:user:add")`；菜单/按钮权限标识与 `sys_menu.perms` 对齐。高危操作（删文件、kill 进程、防火墙开关、删容器、删库）额外启用 **Sa-Token 二级认证**（`StpUtil.checkSafe()`，前端弹二次密码框）。
- **API 前缀**：`/api/v1/**`；WebSocket：`/ws/monitor?satoken=xxx`。

### 5.2 数据库设计（MySQL 8.4，utf8mb4，Flyway 管理 `V1__init.sql` 等）

| 表 | 关键字段 / 说明 |
|---|---|
| `sys_user` | id, username, nickname, password(BCrypt), email, avatar, status, last_login_at；内置 admin |
| `sys_role` / `sys_user_role` / `sys_role_menu` | 标准 RBAC 多对多 |
| `sys_menu` | id, parent_id, name, menu_type(M目录/C菜单/F按钮), path, component, perms, icon, sort, visible —— 直接输出 Vben 后端路由格式 |
| `sys_dict_type` / `sys_dict_data` | 字典 |
| `sys_config` | config_key, config_value（如监控采集间隔、文件根目录白名单） |
| `sys_audit_log` | operator, module, action, method, uri, params, result_code, duration_ms, ip, ua, created_at |
| `sys_login_log` | username, ip, status, message, created_at |
| `mon_metric_hour` | metric_time, cpu, mem, disk_pct, net_in, net_out —— 每小时聚合（实时数据不进 MySQL） |
| `ops_cron_job` | name, cron_expr, command, timeout_sec, status, last_run_at |
| `ops_cron_log` | job_id, output, exit_code, started_at, finished_at |
| `file_recycle_bin` | origin_path, trash_path, file_name, is_dir, size, operator, created_at, expire_at |
| `app_website` | domain, site_name, upstream(json), ssl_enabled, cert_path, conf_path, status |
| `app_database` | db_name, db_user, remark, charset —— 面板代管的库元数据（真实 DDL 在 MySQL 中） |

主键策略 `ASSIGN_ID`（雪花）；逻辑删除 `deleted` 字段（仅配置类表）。

### 5.3 核心模块技术方案

**① monitor（监控）**
- `MetricsCollector`：OSHI `SystemInfo`，`@Scheduled(fixedDelay=5s)`（虚拟线程）采集 CPU/内存/磁盘/网络速率/负载/uptime + 系统静态信息（`/api/v1/monitor/overview`，含 OS、内核、CPU 型号、磁盘列表）。
- 采集结果写入 Redis 环形缓存：`mon:frame:recent`（Redis List，LTRIM 保留最近 720 条=1 小时）。
- `MonitorWebSocketHandler`：订阅者广播最新帧（JSON，3~5s 一帧）；断线由前端重连。
- 历史曲线：每帧不落库，`@Scheduled(cron=0 0 * * *)` 聚合上一小时 → `mon_metric_hour`。

**② file（文件管理器）**
- 基于 `java.nio.file`；**根目录白名单**（默认 `/www,/srv,/var/www`，可在 `sys_config` 修改）；所有路径先 `toRealPath()` 后校验必须位于白名单下（防穿越、防软链逃逸）。
- 能力：目录树/列表（分页、排序）、新建/重命名/移动/删除（入回收站 `~/serverpanel/trash`，7 天过期清理）、上传（multipart 分片可后置）、下载（流式，`Content-Disposition` 编码）、文本预览/编辑（≤2MB 文本判定）、权限修改（`Files.setPosixFilePermissions`）、压缩/解压（zip/tar.gz）。
- 回收站：还原 / 彻底删除 / 清空。

**③ ops（运维工具）**
- 进程：OSHI `os.getProcesses()` 分页列表（pid/name/user/cpu/mem/state/command）；终止用 JDK 原生 `ProcessHandle.of(pid).destroyForcibly()`（不解析/不拼接 shell）。
- systemd 服务：`CommandExecutor` 执行 `systemctl list-units --type=service --all --no-pager --plain -l` 解析为结构化列表；start/stop/restart/enable/disable 走同一封装。
- 计划任务：任务存 `ops_cron_job`；`CronScheduler` 服务内 `@Scheduled(fixedDelay=1s)` 用 cron-utils 计算到期任务并异步执行（虚拟线程），输出/退出码落 `ops_cron_log`；不依赖系统 crond（可观测、可迁移）。
- 防火墙：启动时探测 ufw > firewalld；规则列表解析（`ufw status numbered` / `firewall-cmd --list-rich-rules`）、增删、启停（高危，需二级认证）。

**④ appstack（应用栈）**
- Docker：docker-java（HTTP over unix socket）容器列表/启停/重启/删除/日志（tail）、镜像列表/删除；容器实时 stats 走 docker-java streaming API。
- Nginx 网站：站点配置由 FreeMarker 模板生成到 `/etc/nginx/panel.d/{domain}.conf`（server 块、反代 upstream、静态根目录、SSL）；任何写操作后执行 `nginx -t` 校验，失败回滚，成功 `nginx -s reload`；SSL 支持证书文件上传（certbot 自动签发为 Phase 4 的可选增强）。
- MySQL 数据库：面板持有一个高权限管理连接（`app_admin`，连接串独立配置）；建库/建用户/授权/改密/删库（**库名/用户名正则 `^[A-Za-z0-9_]{1,64}$` 严格校验，杜绝 SQL 注入**，全部用 PreparedStatement 占位符执行 `CREATE DATABASE/USER`、`GRANT`）；备份/恢复调用 `mysqldump`/`mysql` 命令。

**⑤ CommandExecutor（安全命令层，server-framework）**
- 唯一的命令出口：`ProcessBuilder` + **argv 数组**（绝不拼 shell 字符串）、白名单命令表（systemctl/nginx/ufw/mysqldump/firewall-cmd…）、单命令超时（默认 30s）、输出大小上限（2MB 截断）、异步执行返回 `CompletableFuture<ExecResult>`（虚拟线程）。

### 5.4 主要 API 清单（节选，全部 `/api/v1` 下）

```
POST /auth/login | POST /auth/logout | GET  /auth/userinfo | PUT /auth/password
GET  /system/user/page … CRUD            GET/POST/PUT/DELETE /system/role|menu|dict|config
GET  /system/audit-log/page  GET /system/login-log/page
GET  /monitor/overview       WS   /ws/monitor
GET  /file/list?path=        POST /file/mkdir | /file/rename | /file/move
POST /file/upload           GET  /file/download?path=
GET  /file/content          PUT  /file/content
POST /file/compress | /file/extract | /file/perm   DELETE /file (入回收站)
GET  /file/recycle  POST /file/recycle/restore|purge
GET  /ops/process/page      POST /ops/process/{pid}/kill
GET  /ops/service/list      POST /ops/service/{name}/start|stop|restart
CRUD /ops/cron-job          POST /ops/cron-job/{id}/run    GET /ops/cron-log/page
GET  /ops/firewall          POST /ops/firewall/rule  DELETE /ops/firewall/rule/{id}
GET  /docker/containers     POST /docker/container/{id}/start|stop|restart|remove
GET  /docker/container/{id}/logs|stats   GET /docker/images  DELETE /docker/image/{id}
CRUD /nginx/website         POST /nginx/reload   POST /nginx/website/{id}/ssl
CRUD /mysql/database        POST /mysql/backup  POST /mysql/restore
```

---

## 6. 前端详细设计（Vben Admin 5.x / web-antd）

### 6.1 改造要点

1. **入口**：`apps/web-antd` 为唯一业务应用；`packages/@vben/*` 框架包不动；删除 ele/naive/playground 三个应用目录。
2. **对接后端**：
   - `.env.development`：`VITE_GLOB_API_URL=/api`；`vite.config.mts` 代理 `/api → http://localhost:8080`、`/ws → ws://localhost:8080`（ws 用 `vite-proxy` 的 ws:true）。
   - `src/api/request.ts`：响应拦截按 `R<T>` 解包（code!==0 抛错并 toast）；token 头 `Authorization`；401 跳登录。
3. **动态路由**：Vben `preferences` 设 `accessMode: 'backend'`，登录后请求 `/api/v1/auth/userinfo` + 菜单接口，后端按 Vben 约定返回 `component` 映射（`BasicTable` 等）与 meta；按钮权限用 Vben 的 `AccessControl` + `v-perms`。
4. **Sa-Token 配套**（application.yml）：`token-name: Authorization`、`is-read-header: true`、`token-prefix: Bearer`、`is-concurrent: false`；WebSocket 用 query 参数传 token。
5. **图表**：监控页用 `@vben/plugins/echarts`（折线：CPU/内存/网络速率；仪表：磁盘）。
6. **文本编辑**：文件在线编辑引入 CodeMirror 6（`@codemirror/*`，轻量够用）。

### 6.2 页面清单（路由结构）

```
/login 登录页（Vben 内置改造：用户名+密码+验证码开关）
/ 仪表盘      ：监控卡片(CPU/内存/磁盘/负载/在线时长) + 实时折线图 + 系统信息
/system      ：用户管理 / 角色管理 / 菜单管理 / 字典管理 / 参数配置 / 审计日志 / 登录日志
/file        ：文件管理器（左树右表、面包屑、上传/下载/编辑/权限/压缩/回收站抽屉）
/ops         ：进程管理 / 服务管理 / 计划任务(+执行日志) / 防火墙
/appstack    ：Docker(容器/镜像 Tab) / 网站管理(Nginx) / 数据库(MySQL)
```

---

## 7. 安全设计（面板级）

| 威胁 | 对策 |
|---|---|
| 命令注入 | CommandExecutor argv 数组 + 命令白名单，全程无 shell |
| 路径穿越/软链逃逸 | 文件根目录白名单 + `toRealPath()` 归一化校验 |
| SQL 注入（MySQL 管理） | 标识符白名单正则 + PreparedStatement 占位符（DDL 亦不拼接） |
| 暴力破解 | Redis 登录失败计数（5 次锁 15 分钟，按 IP+用户名） |
| 误操作 | 高危操作二级认证（Sa-Token `checkSafe`）+ 前端确认弹窗 + 审计日志 |
| CSRF | token 走 `Authorization` 头（非 Cookie），天然免疫 |
| 传输安全 | 生产建议外层 HTTPS 反代（提供配置说明）；面板默认监听 `127.0.0.1` 或按配置开放 |
| 越权 | 所有接口 Sa-Token 注解校验 perms；前端按钮级 `v-perms` 仅作体验，后端为准 |

---

## 8. 开发环境与部署

- **开发**：`docker compose -f docker-compose.dev.yml up -d`（mysql:8.4 + redis:7-alpine，带初始化库 `server_panel`）；后端 `mvn -pl server-boot -am spring-boot:run`（8080，`dev` profile：Druid 监控页 `/druid/*` 开放）；前端 `cd frontend && pnpm i && pnpm dev`（5173/5666 代理到 8080）。
- **配置文件**：`application.yml`（公共）+ `application-dev.yml` / `application-prod.yml`；敏感项（DB 密码、MySQL 管理账号）用环境变量占位 `${MYSQL_PASSWORD}`。
- **生产单 jar**：`scripts/build.sh` = `pnpm build`（apps/web-antd/dist）→ 拷入 `server-boot/src/main/resources/static` → `mvn clean package`；产物一个 fat jar。
- **安装**：`scripts/install.sh` 生成 `systemd` 服务单元（`/etc/systemd/system/serverpanel.service`，root 运行），`systemctl enable --now serverpanel`。

---

## 9. 实施阶段（按序执行，每阶段有验收标准）

### Phase 0 — 工程脚手架
任务：仓库目录结构；后端父 POM + 7 模块骨架（依赖链、`.editorconfig`、Checkstyle/Spotless 格式化）；Vben 克隆裁剪（删 ele/naive/playground、改标题 Logo 为 ServerPanel）；`docker-compose.dev.yml`；CI 可后置。
验收：`mvn clean install` 全绿（空模块可编译）；`pnpm i && pnpm build` 成功出 dist；compose 起来后 MySQL/Redis 可连通。

### Phase 1 — 核心基座
任务：common（R/异常/错误码/BaseEntity）+ framework（Sa-Token、Druid、MP 分页插件、Redis、CORS、全局异常、审计 AOP）；Flyway `V1__init.sql` 建全部表 + 初始数据（admin/角色/菜单）；auth（登录/登出/userinfo/改密 + 防爆破锁定）；system 用户/角色/菜单/字典/配置/审计/登录日志 CRUD；monitor（OSHI 采集 + Redis 环形缓存 + WS 推送 + overview 接口）；前端：登录页对接、动态路由、布局、仪表盘实时图表、系统管理七个页面。
验收：admin 登录→菜单按权限渲染；仪表盘每 5s 收到 WS 帧；对任意写接口的操作出现在审计日志。

### Phase 2 — 文件管理器
任务：file 模块全部接口（见 §5.3②）+ 前端文件管理页（树+表+上传+编辑器+回收站）。
验收：白名单外的路径（如 `/etc/passwd`）全部返回 403；上传/下载/在线编辑/压缩解压/回收站还原端到端可用；路径穿越用例（`../`、URL 编码、软链）全部被拦截。

### Phase 3 — 运维工具集
任务：进程/服务/计划任务/防火墙后端接口 + 对应前端页面；二级认证接入高危按钮。
验收：能 kill 一个测试进程；systemctl start/stop 生效；创建 `*/1 * * * *` 任务 1 分钟内产生执行日志；防火墙规则增删后 `ufw status` 可见。

### Phase 4 — 应用栈管理
任务：Docker（容器/镜像/日志/统计）+ Nginx 网站（模板生成、`nginx -t` 校验回滚、SSL 上传）+ MySQL（建库/用户/授权/备份）及前端页面。
验收：创建一个反代站点后 `curl` 域名可通（hosts 绑定测试）；配置非法时被 `nginx -t` 拦截且旧配置未破坏；MySQL 建库/授权后用新账号可登录；mysqldump 备份文件可恢复。

### Phase 5 — 打包部署与收尾
任务：`build.sh` 单 jar 合成；`install.sh` systemd 安装；生产 profile（连接池/日志/HTTPS 说明）；README（部署手册）。
验收：全新 Linux 机器上：安装 JDK21 → `install.sh` → systemd 拉起 → 浏览器访问完成登录与各模块冒烟。

---

## 10. 验证步骤（总体冒烟清单）

1. `docker compose -f docker-compose.dev.yml up -d && mvn -pl server-boot -am spring-boot:run` → 日志出现 Flyway `V1__init.sql applied`、Druid `init success`。
2. `curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}'` → 返回 token。
3. 带 token `curl /api/v1/monitor/overview`、`/api/v1/file/list?path=/www` 正常。
4. 前端 `pnpm dev` → 登录 → 仪表盘实时曲线滚动 → 各页面 CRUD 冒烟。
5. Swagger（`/swagger-ui.html`）核对接口契约。
6. 生产包：`scripts/build.sh` → 单 jar → 访问 `http://<host>:8080/` 直接呈现前端页面与 API。

## 11. 假设与风险

- **假设**：面板以 root（或等效 sudo NOPASSWD 白名单）运行；目标机为 Linux 且已装 Nginx/MySQL 客户端（`nginx`、`mysqldump` 在 PATH）；Docker 通过 `/var/run/docker.sock` 可达。
- **风险**：① Vben 5 迭代快，锁定克隆时 commit hash，避免后续 `packages/@vben` 变更引入不兼容；② Sa-Token redis-jackson 与 SB4 data-redis 若有兼容缺口，用手写 `SaTokenDao` 兜底（已预案）；③ 第三方 SB4 starter（Druid/MP/Sa-Token）仍在快速修补期，锁定本文版本，升级需回归冒烟清单；④ OSHI 个别内核版本读数异常属上游问题，监控页做容错降级。
