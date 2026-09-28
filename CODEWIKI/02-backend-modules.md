# 02 后端模块职责与关键类

后端为单体多模块 Maven 工程，根聚合：[backend/pom.xml](file:///workspace/backend/pom.xml)。模块按 **基础层 → 系统层 → 业务层 → 启动层** 分类，依赖单向（上层只依赖下层，`server-boot` 聚合一切）。

模块清单：

```
server-common     ① 基础层 · 公共模型与工具
server-framework  ② 基础层 · 技术框架集成
server-system     ③ 系统层 · 系统管理
server-monitor    ④ 业务层 · 实时监控
server-file       ④ 业务层 · 文件管理
server-ops        ④ 业务层 · 运维管理
server-appstack   ④ 业务层 · 应用栈
server-tools      ④ 业务层 · 工具箱
server-boot       ⑤ 启动层 · 装配启动与打包
```

---

## ① server-common — 公共模型与工具

根包：`com.serverpanel.common`

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `R<T>` | [core/R.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/core/R.java) | 统一响应体，`code/message/data` + `ok/fail` 构造 |
| `PageQuery` | [core/PageQuery.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/core/PageQuery.java) | 分页入参基类 |
| `PageResult<T>` | [core/PageResult.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/core/PageResult.java) | 分页结果 |
| `ErrorCode` | [exception/ErrorCode.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/exception/ErrorCode.java) | 全局错误码枚举（含运维 `5009 HOST_CHANNEL_UNAVAILABLE` 等） |
| `ServiceException` | [exception/ServiceException.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/exception/ServiceException.java) | 业务异常，由全局异常处理器统一兜底 |
| `@Audit` | [annotation/Audit.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/annotation/Audit.java) | 操作审计注解（module/action/risky/safe/recordParams） |
| `IdSourceGateway` / `DbKind` | [id/](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/id/IdSourceGateway.java) | 取号数据源抽象与数据库类型枚举 |
| `InternalTask` | [job/InternalTask.java](file:///workspace/backend/server-common/src/main/java/com/serverpanel/common/job/InternalTask.java) | 内部可调度任务接口 |

---

## ② server-framework — 技术框架集成

根包：`com.serverpanel.framework`
职责：Sa-Token 认证、全局异常、MyBatis-Plus、审计切面（在 server-system）、命令执行、Mongo 基类、SPA 前端转发。

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `SaTokenConfigure` | [config/SaTokenConfigure.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/config/SaTokenConfigure.java) | Sa-Token 认证配置与 Web 拦截规则 |
| `MybatisPlusConfig` | [config/MybatisPlusConfig.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/config/MybatisPlusConfig.java) | MP 分页插件等配置 |
| `MyMetaObjectHandler` | [config/MyMetaObjectHandler.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/config/MyMetaObjectHandler.java) | 审计字段（create_time/update_time 等）自动填充 |
| `BeanConfig` | [config/BeanConfig.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/config/BeanConfig.java) | 公共 Bean 装配 |
| `CommandExecutor` | [command/CommandExecutor.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/command/CommandExecutor.java) | 容器内白名单命令执行器（argv 数组、超时、输出截断） |
| `HostAgentExecutor` | [command/HostAgentExecutor.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/command/HostAgentExecutor.java) | AF_UNIX 宿主代理通道，实现 `HostExecutor` 抽象（能力探测缓存、单行 JSON 协议） |
| `SpaForwardController` | [controller/SpaForwardController.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/controller/SpaForwardController.java) | 尾端兜底托管前端 SPA 静态资源 |
| `MongoBaseService` | [mongo/MongoBaseService.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/mongo/MongoBaseService.java) | MongoDB CRUD 基类 |

---

## ③ server-system — 系统管理

根包：`com.serverpanel.system`
依赖：`server-framework` / `server-common`。承载 RBAC 与登录鉴权。

### Controller（restful，`/api/v1`）

| 类 | 职责 |
| --- | --- |
| [AuthController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/AuthController.java) | 登录、登出、刷新 token |
| [AccountController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/AccountController.java) | 个人账号/资料/安全设置、二级认证 |
| [UserController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/UserController.java) | 用户 CRUD、角色绑定、状态 |
| [RoleController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/RoleController.java) | 角色 CRUD、菜单授权 |
| [MenuController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/MenuController.java) | 菜单/权限树 |
| [DictController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/DictController.java) | 字典类型/字典数据 |
| [ConfigController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/ConfigController.java) | 参数配置 |
| [LogController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/LogController.java) | 操作审计日志、登录日志查询 |
| [CaptchaController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/CaptchaController.java) | 图形/点选验证码 |
| [MailAuthController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/MailAuthController.java) | 邮箱验证码、邮箱登录/注册 |
| [OAuthController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/OAuthController.java) | 第三方 OAuth 登录/绑定 |
| [DashboardController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/DashboardController.java) | 仪表盘聚合数据 |
| [QuickNavController](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/controller/QuickNavController.java) | 快捷导航 |

### Service

| 类 | 职责 |
| --- | --- |
| [AuthService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/AuthService.java) | 登录鉴权、token、登出、注册 |
| [SysUserService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SysUserService.java) | 用户业务（BCrypt、状态、角色） |
| [SysRoleService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SysRoleService.java) | 角色与菜单授权 |
| [SysMenuService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SysMenuService.java) | 菜单树与权限标识 |
| [SysDictService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SysDictService.java) | 字典管理 |
| [SysConfigService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SysConfigService.java) | 参数配置（多模块复用来源） |
| [PermissionService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/PermissionService.java) | 权限计算 |
| [CaptchaService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/CaptchaService.java) | 验证码生成与校验 |
| [MailCodeService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/MailCodeService.java) | 邮箱验证码 |
| [MailSenderService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/MailSenderService.java) | 邮件发送 |
| [SendGuardService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/SendGuardService.java) | 发送频率/防爆破 |
| [oauth/OAuthService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/oauth/OAuthService.java) | 第三方登录（JustAuth） |
| [UserPreferenceService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/UserPreferenceService.java) | 用户偏好（MongoDB） |
| [QuickNavService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/QuickNavService.java) | 快捷导航 |
| [IpRegionService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/IpRegionService.java) | IP 归属地（ip2region） |
| [DashboardService](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/service/DashboardService.java) | 仪表盘聚合 |

### 审计

- [AuditAspect](file:///workspace/backend/server-system/src/main/java/com/serverpanel/system/audit/AuditAspect.java) — `@Audit` 切面实现。

---

## ④-1 server-monitor — 实时监控

根包：`com.serverpanel.monitor`
依赖：`server-framework` / `server-common`。

| 类 | 职责 |
| --- | --- |
| [MetricsCollector](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/service/MetricsCollector.java) | OSHI 指标采集（`@Scheduled` 默认 5s），CPU/内存/磁盘/网络；每小时聚合写 MongoDB |
| [NetworkCollector](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/service/NetworkCollector.java) | 网络流量采集 |
| [MonitorController](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/controller/MonitorController.java) | 监控 API：`/overview` 实时概览、历史聚合 |
| [MonitorWebSocketHandler](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/ws/MonitorWebSocketHandler.java) | WebSocket 实时推送监控帧（`broadcast` 多客户端广播，单连接失败不拖累他人） |
| [MonitorWebSocketConfig](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/ws/MonitorWebSocketConfig.java) | `/ws` 端点注册 + token 握手鉴权 |
| 实体/DTO | [MonMetricHour](file:///workspace/backend/server-monitor/src/main/java/com/serverpanel/monitor/entity/MonMetricHour.java)（小时聚合）、`MetricFrame`(推送帧)、`MonitorOverview`、`NetworkInfo` |

---

## ④-2 server-file — 文件管理

根包：`com.serverpanel.file`
依赖：`server-framework` / `server-common`。

| 类 | 职责 |
| --- | --- |
| [FileController](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/controller/FileController.java) | 文件列表/目录操作/上传下载/在线编辑/压缩解压/删除 |
| [FileService](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/service/FileService.java) | 文件业务核心 |
| [RecycleService](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/service/RecycleService.java) | 回收站管理 + `@Scheduled(cron="0 30 3 * * *")` 每日清理 |
| [RecycleCleanupTask](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/job/RecycleCleanupTask.java) | 实现 `InternalTask`，可手动/自定义 cron 触发 |
| [PathGuard](file:///workspace/backend/server-file/src/main/java/com/serverpanel/file/security/PathGuard.java) | 路径白名单 + `toRealPath()` 防穿越/软链逃逸 |

---

## ④-3 server-ops — 运维管理

根包：`com.serverpanel.ops`
依赖：`server-framework` / `server-common`。
**核心机制**：所有需宿主机能力的操作经 `HostChannelService` → `HostAgentExecutor` → `psm-hostagent` 执行（详见 [04 宿主通道](04-host-channel.md)）。通道不可用时接口抛 `5009`，前端整页只读降级。

| 类 | 职责 |
| --- | --- |
| [HostChannelService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/HostChannelService.java) | 宿主通道门面：`require()` / `call(op, args, label, timeoutSec)`；通道不可用抛 5009 |
| **进程** | [ProcessController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/ProcessController.java) + [ProcessService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/ProcessService.java)：进程列表/详情/kill |
| **服务** | [ServiceController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/ServiceController.java) + [ServiceService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/ServiceService.java)：systemd 单元列表/状态/启停（危险动作分级） |
| **防火墙** | [FirewallController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/FirewallController.java) + [FirewallService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/FirewallService.java)：ufw/firewalld 规则增删、启用、默认策略 |
| **Nginx** | [NginxController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/NginxController.java) + [NginxService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/NginxService.java) + [NginxInstanceService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/NginxInstanceService.java)：实例管理、站点/upstream/stream/证书、`nginx -t` + reload、ACME |
| **证书续期** | [NginxCertRenewScheduler](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/schedule/NginxCertRenewScheduler.java)（每日 03:30）+ [NginxCertRenewTask](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/job/NginxCertRenewTask.java)（InternalTask） |
| **服务器配置** | [ServerConfigController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/ServerConfigController.java) + [ServerConfigService](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/ServerConfigService.java)：sysctl/limits/sshd/timesync drop-in 非侵入配置、9 道闸门 |
| **通道能力** | [HostController](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/controller/HostController.java)：`/ops/host/capability` 能力快照 |

---

## ④-4 server-appstack — 应用栈

根包：`com.serverpanel.appstack`
依赖：`server-framework` / `server-common` + docker-java + postgresql 驱动。

| 类 | 职责 |
| --- | --- |
| [DockerController](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/controller/DockerController.java) + [DockerService](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/service/DockerService.java) | Docker 容器/镜像（docker-java，unix socket） |
| [DatabaseController](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/controller/DatabaseController.java) + [DatabaseService](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/service/DatabaseService.java) | MySQL 建库/账号/备份恢复（白名单库名/账号名） |
| [JobController](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/controller/JobController.java) + [JobService](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/service/JobService.java) | 任务调度中心 |
| [JobLogController](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/controller/JobLogController.java) + [JobLogService](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/service/JobLogService.java) | 任务执行日志 |
| [IdSourceController](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/controller/IdSourceController.java) + [IdSourceService](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/service/IdSourceService.java) | 取号数据源（实现 `IdSourceGateway`，PostgreSQL nextval 取号） |
| [JobScheduler](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/job/JobScheduler.java) | 调度器主循环（`ApplicationRunner`，启动即跑） |
| [DbBackupTask](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/job/internal/DbBackupTask.java) / [JobLogPurgeTask](.../JobLogPurgeTask.java) | 内置调度任务 |
| [JobProperties](file:///workspace/backend/server-appstack/src/main/java/com/serverpanel/appstack/config/JobProperties.java) | 调度配置项 |

---

## ④-5 server-tools — 工具箱

根包：`com.serverpanel.tools`
依赖：`server-framework` + `server-system`（读 sys_config 开关）+ `server-appstack`（复用 JobHttpClient/SSRF 护栏）。**纯计算，不触碰宿主资源。**

| 类 | 职责 |
| --- | --- |
| [CryptoController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/CryptoController.java) + [CryptoService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/CryptoService.java) | 字符串加解密 |
| [ObfuscateController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/ObfuscateController.java) + [ObfuscateService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/ObfuscateService.java) | 可逆混淆 |
| [QrcodeController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/QrcodeController.java) + [QrcodeService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/QrcodeService.java) | 二维码（ZXing 离线） |
| [RegexController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/RegexController.java) + [RegexService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/RegexService.java) | 正则测试 |
| [RegexTemplateController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/RegexTemplateController.java) + [RegexTemplateService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/RegexTemplateService.java) | 正则模板库 |
| [JwtController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/JwtController.java) + [JwtService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/JwtService.java) | JWT 编解码 |
| [IdController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/IdController.java) + [IdService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/IdService.java) | 各类 ID 生成/分析 |
| [ImageConvertController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/ImageConvertController.java) + [ImageConvertService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/ImageConvertService.java) | 图片格式转换（TwelveMonkeys） |
| cert 包 | [CertController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/cert/CertController.java) + [CertParseService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/cert/CertParseService.java)：证书解析 |
| **基础数据** | [BasedataController](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/controller/BasedataController.java)：手机号段/银行卡/行政区划；[BasedataSyncService](.../BasedataSyncService.java) + [ToolsBasedataSyncTask](.../ToolsBasedataSyncTask.java) 实现同步 |
| 归属地 | [RegionService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/RegionService.java) / [RegionLookupService](file:///workspace/backend/server-tools/src/main/java/com/serverpanel/tools/service/RegionLookupService.java) |

---

## ⑤ server-boot — 启动装配

根包：`com.serverpanel`
- POM 聚合全部业务模块（system/monitor/file/ops/appstack/tools）：[server-boot/pom.xml](file:///workspace/backend/server-boot/pom.xml)。
- 启动类 `ServerPanelApplication`（`@SpringBootApplication`）。
- 产物名 `serverpanel` → `server-panel.jar`（spring-boot-maven-plugin 打包 fat jar）。
- 承载 Flyway 迁移（`src/main/resources/db/migration`）与 `application*.yml` 配置、SPA 前端静态资源托管。

## Mapper / 实体

各业务模块内均有 `mapper`（MyBatis-Plus `BaseMapper`）与 `entity`（`@TableName` 映射）。典型如 `SysUserMapper`、`OpsNginxSiteMapper`，多数走 MyBatis-Plus 内置 CRUD + Lambda 查询，遵守"禁 SELECT * / 强制分页 / 参数化查询"规范。