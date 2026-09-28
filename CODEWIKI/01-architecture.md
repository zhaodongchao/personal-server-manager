# 01 整体架构

## 1. 系统定位

ServerPanel 是一套**单机服务器管理系统**（DevOps 面板），为单台 Linux 服务器提供可视化管理。典型同类产品：宝塔面板。

核心能力分层：

- **系统管理**：用户 / 角色 / 菜单 / 字典 / 参数配置 / 操作审计 / 登录日志 / 登录鉴权（含邮箱、OAuth）/ 用户偏好 / 快捷导航 / IP 归属地。
- **实时监控**：CPU / 内存 / 磁盘 / 网络，WebSocket 实时曲线 + 历史聚合。
- **文件管理**：白名单根目录、树表浏览、上传下载、在线编辑、压缩解压、回收站。
- **运维工具**：进程管理、systemd 服务、防火墙（ufw/firewalld）、Nginx 站点、服务器配置（sysctl/limits/sshd/timesync）。
- **应用栈**：Docker 容器/镜像、MySQL 建库/账号/备份恢复、任务调度中心、取号数据源。
- **工具箱**：字符串加解密、可逆混淆、二维码、正则、图片格式转换、JWT、ID 生成、证书解析、基础数据（手机号段/银行卡/行政区划）。

## 2. 架构模式

前后端分离 + 后端单体多模块 + 宿主代理混合托管：

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 前端 | Vben Admin 5 / Vue 3 / Ant Design Vue | 静态 SPA，输出 `dist` 静态资源；开发期经 Vite 代理 `/api`、`/ws` |
| 后端 | Spring Boot 4.1 多模块 Maven | `server-boot` 统一装配；REST API 统一 `/api/v1`，返回 `R<T>` |
| 存储 | MySQL + Redis + MongoDB | MySQL(RBAC/业务表,Flyway 迁移)、Redis(缓存/Sa-Token)、MongoDB(监控历史/服务器配置/变更审计) |
| 宿主通道 | psm-hostagent(Python systemd) | 容器内执行不了的服务/防火墙/Nginx 等系统命令，转由宿主机执行 |

### 后端分层红线

严格遵循单向依赖：`Controller → Service → Mapper/外部能力 → Database`。

- **Controller** 仅请求接入、参数校验、统一 `R<T>` 封装，禁止业务逻辑。
- **Service** 承载业务与事务。
- **Mapper** 仅数据库交互（MyBatis-Plus / SQL）。
- **层级依赖**：业务/系统模块 → `server-framework` / `server-common`，禁止反向或成环。

## 3. 请求链路示例

```
浏览器 ──POST /api/v1/auth/login──▶ AuthController（参数校验）
                                      └─▶ AuthService（BCrypt 校验、Sa-Token 签发 token）
                                              └─▶ Mapper 查询 sys_user 表（MySQL）
                                    返回 R<TokenInfo>
浏览器 ──GET /api/v1/monitor/overview (Authorization: Bearer <token>)──▶
   SaTokenConfigure 拦截校验登录 ─▶ MonitorController ─▶ MetricsCollector(OSHI) → R<MonitorOverview>
浏览器 ──WebSocket /ws──▶ MonitorWebSocketHandler（实时推送监控帧）
```

## 4. 认证授权（Sa-Token）

- 配置文件：[SaTokenConfigure.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/config/SaTokenConfigure.java) 实现 `WebMvcConfigurer`。
- `/api/**` 默认要求登录；放行登录、验证码、OAuth 授权/登录等接口。
- 细粒度权限由 Controller 方法上的 `@SaCheckPermission("xxx")` 控制。
- 客户端携带 `Authorization: Bearer <token>`；token 存 Redis（`sa-token-redis-jackson`）。
- 前置接口：

```java
// SaTokenConfigure 核心约定（示意）
SaHolderConfig: tokenName=token / isReadHeader=true
拦截规则: addInterceptors(new SaInterceptor(...).addPathPatterns("/api/**"))
          .excludePathPatterns(登录、验证码、OAuth 等)
```

## 5. 关键设计机制

### 5.1 统一响应体与异常

- 统一响应体 `R<T>`（`code/message/data`）与 `PageQuery`/`PageResult`：[core 包](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/core/R.java)。
- `ServiceException` + `ErrorCode` 枚举统一错误语义：[ErrorCode.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/exception/ErrorCode.java)。
- 全局异常统一拦截在 `server-framework` 的 `GlobalExceptionHandler`。

### 5.2 安全命令执行（双通道）

- **容器内直连**：[CommandExecutor.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/command/CommandExecutor.java) —— 内建命令白名单（`ps/kill/systemctl/nginx/ufw/mysql/df` 等），支持 `serverpanel.command.extra-whitelist` 扩展；**argv 数组直传**（无 shell 拼接），带超时与输出大小限制；敏感信息经环境变量传递。
- **宿主机代理**：[HostAgentExecutor.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/command/HostAgentExecutor.java) —— 经 AF_UNIX socket 与 `psm-hostagent` 通信（详见 [04 宿主通道](04-host-channel.md)）。
- 二者共用一个 `HostExecutor` 抽象，运维模块通过 `HostChannelService` 统一取通道。

### 5.3 文件路径安全

[PathGuard.java](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/security/PathGuard.java)：只接受绝对路径、归一化 `../`、必须位于 `serverpanel.file.roots` 白名单下、用 `toRealPath()` 防软链逃逸。`resolveExisting`（读/删/重命名源）与 `resolveNew`（新建/上传/解压目标）两类入口分别校验。

### 5.4 操作审计

[@Audit 注解](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/annotation/Audit.java) + [AuditAspect.java](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/audit/AuditAspect.java)：`module/action/risky/safe/recordParams`，用于写操作审计、**高危操作**标记与入参记录控制。

### 5.5 定时任务

- Spring `@Scheduled`：监控采集（`MetricsCollector`，默认 5s）、回收站清理（每日 03:30）、Nginx 证书续期（每日 03:30）。
- **内部任务** `InternalTask` + 调度中心 `JobScheduler`（`server-appstack`）：`DbBackupTask`、`JobLogPurgeTask`、`RecycleCleanupTask`、`NginxCertRenewTask`、`ToolsBasedataSyncTask` 等，支持 Web 可视化 cron 触发。
- 通用调度实现：[JobScheduler.java](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/job/JobScheduler.java)。

### 5.6 前端状态/路由

- 认证状态：Pinia `src/store/auth.ts`（authByToken / authLogin / logout）。
- 路由守卫：[guard.ts](file:///workspace/frontend/apps/web-antd/src/router/guard.ts) 校验 accessToken、生成用户菜单/路由、登录重定向。
- 访问模式 `accessMode: 'backend'`（后端路由控制，偏好见 [preferences.ts](file:///workspace/frontend/apps/web-antd/src/preferences.ts)）。

## 6. 配置分层（backend/server-boot/src/main/resources）

- `application.yml`：公共配置（端口、MyBatis-Plus、Sa-Token、springdoc、`serverpanel.*` 面板自有配置）。
- `application-dev.yml` / `application-prod.yml`：环境差异化。
- 敏感配置（DB/Redis/MongoDB 密码）通过环境变量注入，占位符形式保留，禁止明文入库。
- Flyway 迁移脚本位于 `server-boot/src/main/resources/db/migration`。

## 7. 重要踩坑纪要

记录在仓库 [tasks/lessons.md](../tasks/lessons.md)：实体字段名会成为 MyBatis-Plus SELECT 别名（`binary` 是 MySQL 保留字需命名 `binaryPath`）等。