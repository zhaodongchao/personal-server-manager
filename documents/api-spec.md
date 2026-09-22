# ServerPanel 前后端 API 接口规范

本文档定义 ServerPanel 后端与前端（Vben Admin 5）的全部对接契约，涵盖通用约定、认证、各业务模块 REST 接口、WebSocket 推送以及前端接入要点。所有接口定义均与后端 Controller 源码逐一核对。

## 一、通用约定

### 1. 基础信息

| 项 | 约定 |
| ---- | ---- |
| Base URL | `/api/v1`（前端生产 `VITE_GLOB_API_URL=/api/v1`） |
| 数据格式 | `application/json;charset=UTF-8` |
| 上传 | `multipart/form-data` |
| 认证 | `Authorization: Bearer <accessToken>` 请求头 |
| 鉴权模型 | `/api/**` 默认需登录；按钮级权限由接口上的 `@SaCheckPermission` 声明 |
| 接口文档 | `/swagger-ui.html`（OpenAPI 3，生产可关） |

### 2. 统一响应结构 `R<T>`

所有 REST 接口返回统一包装：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `code` | int | `0` 成功；非 0 为错误码（见下表） |
| `message` | string | 成功/失败提示语，前端失败时直接 toast |
| `data` | object | 业务数据，可为 `null` |

> 例外：**文件下载**接口直接返回二进制流（`Content-Disposition` 附件），不走 R 包装。

### 3. 错误码分段

| 区间 | 模块 | 常见码 |
| ---- | ---- | ---- |
| 0 / 4xx / 5xx | 通用 | `0` 成功、`400` 参数、`401` 未登录、`403` 无权限、`404` 不存在、`500` 内部错误 |
| 1xxx | 认证 | `1001` 账号或密码错误、`1002` 失败锁定、`1003` 停用、`1004` 原密码错、`1010` 敏感操作需先完成二级认证（step-up） |
| 2xxx | 系统管理 | `2001` 用户已存在、`2002` 角色已存在、`2003` 内置数据、`2004` 不能删自己、`2005` 有子菜单、`2006` 角色在用、`2007/2008` 字典重复、`2009` 参数键重复 |
| 3xxx | 监控 | `3001` 采集器未就绪 |
| 4xxx | 文件 | `4001` 路径越权、`4002` 目标已存在、`4003` 源路径不存在、`4004` 根目录受限、`4005` 文件过大、`4006` 非文本、`4007` 压缩格式不支持、`4008` 回收站记录失效、`4009` 权限非法、`4010` 目录非空 |
| 5xxx | 运维 | `5001` 命令不在白名单、`5002` 超时、`5003` 进程不存在、`5004` 服务不可管理、`5007` 无可用防火墙、`5008` 规则不存在、`5009` 宿主通道不可用、`5010` 宿主通道版本不匹配（已降级只读）、`5011` 服务操作被保护清单拦截、`5014` 防火墙规则编号已变化、`5015` 该操作会切断 SSH/面板访问（需二次确认）、`5016` 目标规则由外部程序托管（禁止面板删除）、`5017` 防火墙变更不可回滚 |
| 6xxx | 应用栈 | `6001` Docker 不可用、`6002` 资源不存在、`6004` Nginx 不可用、`6005` 配置校验失败、`6006` MySQL 管理连接不可用、`6007` 标识符非法、`6008` 库已存在、`6009` 库不存在 |
| 6xxx | Nginx 管理 | `6010` 实例不存在、`6011` 危险操作需二次确认（confirm 关键字）、`6012` ACME 模式不支持、`6013` DNS-01 仅支持通配符、`6014` 证书不存在或状态非法、`6015` 配置渲染/校验失败 |
| 6xxx | 服务器配置 | `6020` 配置类别不存在、`6021` 预演校验未通过、`6022` 生效失败（已自动回滚）、`6023` 备份失败、`6024` 高风险变更需键入关键字（防自锁护栏）、`6025` 配置项取值非法或违反安全规则、`6026` 该配置类别正在生效中 |
| 6xxx | 定时任务 | `6030` 任务不存在、`6031` cron 非法或间隔过短（最小 10 秒）、`6032` 任务执行中或执行器仍被引用、`6033` 处理器参数不合法、`6034` 执行器不存在或不可用、`6035` 内置执行器受保护（不可删除 / 停用）、`6036` 命令不在白名单、`6037` 日志不存在、`6038` 任务名或执行器 AppName 已存在、`6039` 需确认关键字、`6040` 任务未被执行（被阻塞策略或前置检查拦下） |

### 4. 分页结构与参数

分页接口的 `data` 统一为：

```json
{
  "records": [ ],
  "total": 100,
  "pageNum": 1,
  "pageSize": 10
}
```

查询参数统一为 `pageNum`（默认 1）、`pageSize`（默认 10，上限 200，后端钳制）。各分页接口追加业务过滤参数（`keyword` / `username` 等，均可选）。

## 二、认证与账户

### 1. 登录

```
POST /api/v1/auth/login        # 无需登录
```

请求体：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `username` | string | 必填 |
| `password` | string | 必填 |

响应 `data`：

```json
{ "accessToken": "8f8a5435-..." }
```

> 连续失败 5 次锁定 15 分钟（`serverpanel.login` 配置）。

### 2. 登出

```
POST /api/v1/auth/logout
```

返回 `R<Void>`。前端登出流程最后调用。

### 3. 权限码（按钮级）

```
GET /api/v1/auth/codes
```

响应 `data`：`string[]`，如 `["system:user:list", "file:delete", ...]`。前端据此控制按钮显隐。

### 4. 刷新 token

```
POST /api/v1/auth/refresh
```

续期当前会话（Sa-Token `renewTimeout(86400)`）。响应 `data`：

```json
{ "accessToken": "续期后的 token" }
```

### 5. 当前用户信息

```
GET /api/v1/user/info
```

响应 `data`（UserInfoVO，字段名与 Vben 约定一致）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `userId` | string | 用户 ID |
| `username` | string | 用户名 |
| `realName` | string | 昵称 |
| `desc` | string | 描述 |
| `avatar` | string | 头像 |
| `homePath` | string | 默认首页 |
| `roles` | string[] | 角色标识 |

### 6. 修改当前用户密码

```
PUT /api/v1/user/password
```

请求体：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `oldPassword` | string | 必填 |
| `newPassword` | string | 必填 |

### 7. 动态路由树（Vben 后端模式）

```
GET /api/v1/menu/all
```

响应 `data`：`RouteVO[]`，树形结构，供前端 `generateRoutesByBackend` 消费：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `component` | string | 组件标识（如 `basic` / 页面路径） |
| `path` | string | 路由路径 |
| `name` | string | 路由名 |
| `redirect` | string | 重定向 |
| `meta.title` | string | 菜单标题 |
| `meta.icon` | string | 图标 |
| `meta.order` | number | 排序 |
| `meta.hideInMenu` | boolean | 是否隐藏 |
| `children` | RouteVO[] | 子路由 |

### 8. 偏好设置（按用户维度，存 MongoDB）

```
GET /api/v1/user/preference
```

无需权限码，仅需登录（与 `/user/info` 一致）。响应 `data`：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `preferences` | object | Vben preferences 全量 JSON（13 组） |
| `custom` | object | 自定义扩展偏好（4 字段），可空 |

> 用户从未配置过时，返回**后端写死的默认配置**（`DefaultPreferenceConfig`），恒非空。

```
PUT /api/v1/user/preference
```

请求体（全量覆盖，upsert 一人一档）：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `preferences` | object | 必填，偏好全量 JSON |
| `custom` | object | 可选，自定义扩展偏好 |

### 9. 二级认证（step-up）

高危端点（`@Audit(safe = true)`）在执行前要求一次「二级认证」：当前登录会话需在最近
`serverpanel.audit.safe-timeout-seconds`（默认 300）秒内通过口令复核。

```
POST /api/v1/auth/safe
```

请求体：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `password` | string | 必填，当前账号密码 |

响应 `data`：`{ "timeoutSeconds": 300 }`。成功后在**当前 token 会话**上打开安全窗口（`StpUtil.openSafe`），
窗口内可重复执行高危操作，无需再次输入。

未通过二级认证时，高危端点返回 `HTTP 200` + `code = 1010`；前端 `api/request.ts` 捕获 1010 后弹出
密码框，认证成功后**自动重放原请求**（带 `__safeRetry` 标记，避免窗口过期时无限弹框）。

> 安全设计：`/auth/safe` **不写入 `sys_audit_log`**（否则明文口令会入审计表），认证事件记入 `sys_login_log`；
> 口令错误计入登录防爆破计数（`serverpanel.login.max-fail`，默认 5 次锁 15 分钟）。
> 总开关 `serverpanel.audit.enforce-safe` 置 `false` 可全局关停该闸门（无需发版）。

> 标注口径：`@Audit(risky = true)` 仅为**标记**（落 `sys_audit_log.risky`，前端展示「高危」标签）；
> 真正触发二级认证的是 `@Audit(safe = true)`。两者默认均不改变接口行为（`safe` 逐端点开通）。

## 三、系统管理 `/api/v1/system`

### 1. 用户管理 `system/user`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?username=&status=` | `system:user:list` | 分页 |
| GET | `/{id}` | `system:user:list` | 详情 |
| GET | `/{id}/role-ids` | `system:user:list` | 已绑定角色 ID 列表 |
| POST | `/` | `system:user:add` | 新增 |
| PUT | `/{id}` | `system:user:edit` | 编辑 |
| DELETE | `/{id}` | `system:user:delete` | 删除（高危审计） |

UserBody（新增/编辑共用）：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `password` | string | 仅新增；编辑忽略 |
| `username` | string | 必填，3-30 位，字母开头 |
| `nickname` | string | 必填，≤30 |
| `email` / `phone` | string | 可选 |
| `status` | number | 1 启用 / 0 停用 |
| `roleIds` | number[] | 角色 ID 列表 |

### 2. 角色管理 `system/role`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?roleName=` | `system:role:list` | 分页 |
| GET | `/all` | `system:role:list` | 启用角色列表（选择器） |
| GET | `/{id}/menu-ids` | `system:role:list` | 已授权菜单 ID 列表 |
| POST | `/` | `system:role:add` | 新增 |
| PUT | `/{id}` | `system:role:edit` | 编辑（同时重绑菜单） |
| DELETE | `/{id}` | `system:role:delete` | 删除（高危审计） |

RoleBody：`roleName`（必填）、`roleKey`（必填，字母开头）、`sort`、`status`、`remark`、`menuIds`（number[]）。

### 3. 菜单管理 `system/menu`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/tree` | `system:menu:list` | 菜单树 |
| POST | `/` | `system:menu:add` | 新增 |
| PUT | `/{id}` | `system:menu:edit` | 编辑 |
| DELETE | `/{id}` | `system:menu:delete` | 删除（高危审计） |

MenuBody：`parentId`（必填，0 为根）、`menuName`（必填）、`menuType`（必填，`M`目录/`C`菜单/`F`按钮）、`routePath`、`component`、`perms`、`icon`、`sort`、`visible`、`status`。

### 4. 字典管理 `system/dict`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/type/page?keyword=` | `system:dict:list` | 字典类型分页 |
| POST | `/type` | `system:dict:add` | 新增类型 |
| PUT | `/type` | `system:dict:edit` | 编辑类型 |
| DELETE | `/type/{id}` | `system:dict:delete` | 删除类型（高危审计） |
| GET | `/data/page?dictType=` | `system:dict:list` | 字典数据分页 |
| GET | `/data/type/{dictType}` | 仅需登录 | 按类型取启用数据（下拉源） |
| POST | `/data` | `system:dict:add` | 新增数据 |
| PUT | `/data` | `system:dict:edit` | 编辑数据 |
| DELETE | `/data/{id}` | `system:dict:delete` | 删除数据 |

类型/数据实体均含 `dictType`、`dictLabel`、`dictValue`、`sort`、`status`、`remark` 等字段。

### 5. 参数配置 `system/config`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=` | `system:config:list` | 分页 |
| POST | `/` | `system:config:edit` | 新增 |
| PUT | `/` | `system:config:edit` | 编辑 |
| DELETE | `/{id}` | `system:config:delete` | 删除（高危审计） |

SysConfig 字段：`configName`、`configKey`、`configValue`、`configType`（Y 内置/N 自定义）、`remark`。内置参数（`configType=Y`）删除/修改受 `2003` 保护。

### 6. 日志查询 `system/`（只读）

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/audit-log/page?operator=&module=&bizCode=` | `system:audit:list` | 操作审计分页（`module=access` 为越权尝试留痕） |
| GET | `/login-log/page?username=&status=` | `system:loginlog:list` | 登录日志分页 |

审计日志 `AuditLogVO` 关键字段：`module`（`file` / `ops` / `appstack` / `system`，越权尝试为 `access`）、`action`、
`operator`、`method`、`uri`、`requestMethod`、`params`、`resultCode`（`0` 成功 / `403` 被拒 / `500` 业务异常）、
`bizCode`（业务码；成功为 `null`，`1010` 未完成二级认证，`403` 越权尝试，其余为 4xxx~6xxx）、
`risky`（`1` = `@Audit(risky = true)`）、`error`、`costMs`、`ip`、`userAgent`、`createdAt`。

> 越权尝试（`module=access`）由鉴权层单独留痕：`result_code=403`、`biz_code=403`、`method=""-""`、`params=NULL`
> （`@SaCheckPermission` 先于 `@Audit` 生效，故不能靠 `@Audit` 记录；也不把 `@Audit` 前移，否则会把「未执行」记成「已执行」）。

## 四、监控 `/api/v1/monitor`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/overview` | `dashboard:view` | 系统概览 + 最新帧 |
| GET | `/history?minutes=60` | `dashboard:view` | 历史帧（1-60 分钟，钳制） |
| GET | `/network` | `dashboard:view` | 网络信息快照（宿主机网卡明细 + Docker 虚拟网络 + 汇总） |

`/overview` 响应 `data`（MonitorOverview）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `hostname` / `os` / `kernel` / `cpuModel` | string | 主机信息 |
| `cpuPhysicalCores` / `cpuLogicalCores` | number | 核数 |
| `disks[]` | array | 真实挂载的文件系统 `{mount, source(源设备,如 /dev/mapper/vg0-root), fsType, vg(所属 LVM 卷组,非 LVM 为空), totalBytes, usableBytes, usage}`；伪文件系统（proc/sysfs/overlay 等）与 loop/ram 源不返回 |
| `physicalDisks[]` | array | 真实物理磁盘（排除 loop/dm/ram 等虚拟设备）`{name, model, serial, sizeBytes, partitions[]{name,mount,sizeBytes,type(文件系统类型),vg(作为 PV 时所属卷组)}}` |
| `deviceMappers[]` | array | Device Mapper 设备（LVM 逻辑卷映射 / dm-* 等）`{name(/dev/mapper/vg0-root), vg, sizeBytes, fsType, mount}` |
| `lvm` | object | LVM 信息 `{physicalVolumes[]{name,vg,sizeBytes,freeBytes}, volumeGroups[]{name,pvCount,lvCount,sizeBytes,freeBytes}, logicalVolumes[]{name(设备映射名 vg0-root),vg,sizeBytes,fsType,mount}}`；非 LVM 环境各列表为空 |
| `interfaces[]` | array | 宿主机网卡明细（含物理网卡、网桥与容器 veth），与 `/network` 的同名字段同构，字段见下节 |
| `dockerNetworks[]` | array | Docker 虚拟网络（含接入的容器端点），与 `/network` 同构 |
| `networkSummary` | object | 网络汇总统计，与 `/network` 同构 |
| `latest` | MetricFrame | 最新一帧 |

> 磁盘/LVM 数据采集方式：
> `lsblk -J -b`（物理磁盘+分区+Device Mapper+LVM 拓扑，普通权限执行）；
> 挂载点/文件系统直读 `<host-sysroot>/proc/mounts`（容量经 statfs）；
> `pvs/vgs/lvs --reportformat json`（sudo，PV/VG 明细）。命令受 `CommandExecutor` 白名单约束。
> LVM 工具或 sudo 不可用时（典型如容器部署）回退 lsblk + sysfs 拓扑推导（dm 设备真名经
> `/sys/block/dm-*/dm/name`、LVM 判定经 `dm/uuid` 的 `LVM-` 前缀），挂载点/格式化经挂载表回填，保证展示不为空。
>
> **容器部署**（面板跑在 Docker 内）需将宿主机根递归挂载进容器并配置 `HOST_SYSROOT`，否则只能看到容器自身的挂载与设备：
>
> ```yaml
> services:
>   serverpanel:
>     volumes:
>       - /:/host            # 宿主机根递归挂载（docker -v 为 rbind，含 /proc /sys /dev）
>     environment:
>       HOST_SYSROOT: /host  # 未配置时自动探测 /host、/hostfs、/mnt/host
> ```
>
> 裸机 / systemd 部署无需配置（`host-sysroot` 留空）。

### 网络信息快照 `/network`

`/overview` 内嵌的 `interfaces` / `dockerNetworks` / `networkSummary` 与本节完全同构；
网卡明细字段多、体量大，不适合塞进 Redis 环形缓存与 WebSocket 的每帧推送，故单列端点。

响应 `data`（NetworkInfo）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `ts` | number | 采集时间戳（毫秒） |
| `summary` | NetworkSummary | 汇总统计，见下表 |
| `interfaces[]` | NetInterface[] | 宿主机网卡明细（含物理网卡、网桥与容器 veth），已按类型排序 |
| `dockerNetworks[]` | DockerNetwork[] | Docker 虚拟网络（含接入的容器端点） |
| `error` | string \| null | 采集异常时的可读说明；正常为 `null`，前端据此展示告警条而非空白 |

NetInterface（单块网卡；容器部署时经 `HOST_SYSROOT` 读宿主机 `/sys` 与 `/proc`，即宿主机内核视角）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `name` / `index` | string / number | 内核接口名（`enp6s0` / `docker0` / `br-xxxx` / `vethxxxx`）与 ifindex |
| `category` | string | 接口分类：`physical` / `bond` / `bridge` / `veth` / `tunnel` / `virtual` / `loopback` |
| `typeLabel` | string | 类型中文描述（含 Docker 语义），供页面直接展示 |
| `operState` | string | 管理状态：`up` / `down` / `unknown` / `lowerlayerdown` 等 |
| `up` | boolean | 管理 UP **且** 链路连通（`carrier`） |
| `adminUp` / `carrier` | boolean | 管理 UP（IFF_UP）/ 是否检测到载波（物理链路连通） |
| `loopback` / `bridge` / `bond` | boolean | 回环 / 网桥 / 绑定设备标识 |
| `master` | string | 上层设备名（veth 挂在网桥上时为其网桥名） |
| `bridgePorts[]` | string[] | 网桥端口成员名（仅网桥非空） |
| `dockerRelated` | boolean | 是否与 Docker 相关（`docker0` / `br-*` 网桥及其上的 veth） |
| `dockerNetwork` | string | 关联的 Docker 网络名（能解析到时非空） |
| `mac` / `mtu` | string / number | MAC 地址 / MTU |
| `ipv4` / `cidr` | string | 主 IPv4（兼容字段）/ 主地址 CIDR（优先 IPv4，无则取首个 IPv6） |
| `ipv4List[]` / `ipv6List[]` | string[] | 全部 IPv4 / IPv6 地址（带前缀长度） |
| `speed` / `duplex` | number / string | 链路速率 Mbps（未知为 `-1`）/ 双工 `full`、`half`、`unknown` |
| `driver` / `busInfo` / `vendorId` / `vendor` / `alias` | string | 内核驱动 / 总线地址（物理网卡为 PCI 槽位）/ 设备标识 / 厂商名 / ifalias |
| `rxBytes` / `txBytes` / `rxPackets` / `txPackets` | number | 累计收发字节与包数 |
| `rxErrors` / `txErrors` / `rxDropped` / `txDropped` | number | 收发错误与丢弃计数 |
| `rxRate` / `txRate` | number | 实时收发速率 KB/s（与上一快照的差分值，首帧为 0） |
| `rxPacketRate` / `txPacketRate` | number | 收发包速率 包/s（差分值） |

DockerNetwork（对应 `docker network inspect` 的关键字段）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `id` / `name` | string | 网络 ID（12 位短 ID）/ 网络名（`bridge` / `host` / `none` / 自定义名） |
| `driver` / `scope` | string | 驱动（`bridge` / `host` / `overlay` / `null`）/ 作用域（`local` / `swarm`） |
| `internal` / `attachable` / `ipv6Enabled` | boolean | 内部网络 / 允许手动挂载容器 / 启用 IPv6 |
| `subnet` / `gateway` | string | 子网 CIDR（如 `172.22.0.0/16`）/ 网关地址 |
| `bridgeName` | string | 宿主侧网桥名（`docker0` / `br-xxxx`；`host`、`none` 等无网桥时为空串） |
| `createdAt` | number | 网络创建时间（毫秒时间戳，未知为 0） |
| `containerCount` | number | 接入本网络的容器数 |
| `containers[]` | NetworkAttachment[] | 容器端点 `{containerId, containerName, ipv4(网络内 IPv4), mac}` |

NetworkSummary（汇总统计；`total` 与累计流量均不含回环）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `total` / `up` | number | 接口总数 / 可用接口数（管理 UP 且链路连通） |
| `physical` / `bridge` / `veth` | number | 物理网卡 / 网桥 / 容器虚拟网卡数 |
| `dockerRelated` | number | 与 Docker 相关的接口数 |
| `dockerNetworks` / `dockerContainers` | number | Docker 网络数 / 接入容器数（按容器去重） |
| `totalRxBytes` / `totalTxBytes` | number | 全部接口累计接收 / 发送字节 |
| `rxRate` / `txRate` | number | 全部接口实时接收 / 发送速率合计 KB/s |
| `defaultGateway` / `defaultInterface` | string | 默认网关（IPv4，无默认路由为空串）/ 默认出口网卡名 |

> 网络数据采集方式（不依赖容器内 `ip` / `docker` CLI）：
> 接口名与收发计数读 `<host-sysroot>/proc/net/dev`（含错误与丢弃列），IPv6 前缀读
> `<host-sysroot>/proc/net/if_inet6`，默认网关读 `<host-sysroot>/proc/net/route`（小端十六进制转 IPv4）；
> 类型、驱动、总线、网桥端口与上层设备由 sysfs 推导
> （`/sys/class/net/<if>/device/uevent` 取 DRIVER / PCI_SLOT_NAME / PCI_ID，
> `/sys/class/net/<br>/brif/` 取网桥端口，`/sys/class/net/<veth>/master` 指向所属网桥）；
> Docker 网络经 docker-java 调 `listNetworksCmd()`（30s 缓存，避免每 5s 打爆 docker.sock），
> 宿主网桥名按 `com.docker.network.bridge.name` 选项 → `docker0` → `br-<id 前 12 位>` 依次推导。
>
> 采集异常（如 Docker 未安装、`docker.sock` 未挂载）不影响其余字段，仅写入 `error` 或使
> `dockerNetworks` 为空数组。

MetricFrame（`/history` 返回数组，WebSocket 推送同构）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `ts` | number | 时间戳（毫秒） |
| `uptimeSeconds` | number | 开机时长 |
| `cpuUsage` | number | CPU 使用率 % |
| `memTotal` / `memUsed` / `memUsage` | number | 内存字节 / 使用率 % |
| `loadAvg1` / `loadAvg5` / `loadAvg15` | number | 负载 |
| `netInRate` / `netOutRate` | number | 网络速率 bytes/s |
| `diskTotal` / `diskUsed` / `diskUsage` | number | 磁盘字节 / 使用率 % |

### WebSocket 实时推送

- 端点：`ws://<host>/ws/monitor`
- 认证：URL query 传 token（浏览器 WS 无法带 Header）：`ws://<host>/ws/monitor?token=<accessToken>`
- 握手失败返回 HTTP 401。
- 服务端以 `serverpanel.monitor.interval-seconds`（默认 5s）间隔广播 `MetricFrame` JSON。
- 前端连接示例：

```js
const ws = new WebSocket(`ws://${location.host}/ws/monitor?token=${accessToken}`);
ws.onmessage = (e) => { const frame = JSON.parse(e.data); /* 更新曲线 */ };
```

## 五、文件管理 `/api/v1/file`

### 1. 查询

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/roots` | `file:list` | 白名单根目录 `FileEntry[]` |
| GET | `/list?path=` | `file:list` | 目录内容 `FileEntry[]` |
| GET | `/content?path=` | `file:edit` | 在线编辑内容（仅文本） |
| GET | `/download?path=` | `file:list` | **二进制下载流**（非 R 包装） |
| GET | `/recycle?pageNum=&pageSize=&keyword=` | `file:list` | 回收站分页 |

FileEntry：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `name` | string | 名称 |
| `path` | string | 绝对路径 |
| `dir` | boolean | 是否目录 |
| `size` | number | 字节数 |
| `lastModified` | number | 毫秒时间戳 |
| `perms` | string | 权限串（如 `rwxr-xr-x`） |
| `text` | boolean | 是否可在线编辑 |

### 2. 写操作（全部带审计）

| 方法 | 路径 | 权限 | 请求体/参数 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| POST | `/mkdir` | `file:mkdir` | `{path}` | 建目录 |
| POST | `/rename` | `file:edit` | `{path, newName}` | 重命名 |
| POST | `/move` | `file:edit` | `{sourcePath, targetDir}` | 移动 |
| POST | `/upload` | `file:upload` | `dir`(param) + `files`(file[]) | 上传，返回 `data: 成功数` |
| PUT | `/content` | `file:edit` | `{path, content}` | 保存编辑 |
| POST | `/perm` | `file:perm` | `{path, mode}` | 改权限 |
| POST | `/compress` | `file:compress` | `{path, format}` | 压缩（zip/tar.gz） |
| POST | `/extract` | `file:compress` | `{archivePath, targetDir}` | 解压 |
| DELETE | `?path=` | `file:delete` | query 参数 | 删除到回收站（高危审计） |

### 3. 回收站操作（全部高危审计）

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| POST | `/recycle/restore/{id}` | `file:delete` | 还原 |
| DELETE | `/recycle/{id}` | `file:delete` | 彻底删除 |
| POST | `/recycle/empty` | `file:delete` | 清空回收站（**需二级认证**：`@Audit(safe = true)`，未认证返回 `1010`） |

> 安全约束：所有路径必须位于 `PANEL_FILE_ROOTS` 白名单内，后端做 `toRealPath()` 归一化校验。

## 六、运维工具 `/api/v1/ops`

### 1. 进程管理 `ops/process`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/list?keyword=` | `ops:process:list` | 进程列表 `ProcessInfo[]` |
| POST | `/{pid}/kill` | `ops:process:kill` | 结束进程（高危审计） |

ProcessInfo：`pid`(number)、`user`、`cpu`、`mem`、`stat`、`elapsed`、`cmd`。

### 2. systemd 服务管理 `ops/service`

> 服务页依赖宿主机能力（systemctl / journalctl），容器内没有这些命令，因此本节全部接口
> 经**宿主执行通道**（见 §6.5）执行；通道不可用时写接口返回 `5009`，前端整页只读降级。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=&active=&unitFileState=&failedOnly=&includeAlias=&pageNum=&pageSize=` | `ops:service:list` | 分页列表（推荐，含未加载单元） |
| GET | `/list?keyword=` | `ops:service:list` | 全量列表（兼容保留） |
| GET | `/failed` | `ops:service:list` | 失败单元聚合（页面顶部告警条） |
| GET | `/summary` | `ops:service:list` | 总览统计（统计卡） |
| GET | `/protected` | `ops:service:list` | 保护清单（前端提前标识） |
| GET | `/{name}` | `ops:service:list` | `systemctl status` 原文 |
| GET | `/{name}/detail` | `ops:service:list` | 结构化详情（基本/PID/依赖/日志四块） |
| GET | `/{name}/logs?lines=&since=&priority=` | `ops:service:log` | journal 日志（可筛选） |
| POST | `/{name}/{action}` | `ops:service:manage` | start/stop/restart/reload/enable/disable（高危审计） |
| POST | `/{name}/action` | `ops:service:manage` | 动作 + options（推荐） |
| POST | `/daemon-reload` | `ops:service:manage` | systemd daemon-reload |
| POST | `/batch` | `ops:service:batch` | 批量操作（高危审计） |

`ServiceVO`：`name`、`load`、`active`、`sub`、`description`、`enabled`，
以及实时字段 `mainPid`、`memoryBytes`、`uptimeSeconds`、`restarts`、`failedSinceEpoch`。

单元名合法性 `^[A-Za-z0-9_@\-][A-Za-z0-9_.@:\-]{0,254}$`——systemd 会把 `-` 转义成
`\x2d`，根挂载点 `-.mount` 以 `-` 开头；模板单元（`xxx@.service`）在 `list-unit-files`
里合法但无法被 `systemctl show`，**批量查询整批会因此中止**，后端会先过滤模板单元、
失败时按二分降到单单元重试。

### 3. 防火墙 `ops/firewall`

> 除「读」以外全部是高危动作：读需要 `ops:firewall:list`，增删规则需要 `ops:firewall:write`，
> 全局开关/看门狗需要 `ops:firewall:danger`，回滚需要 `ops:firewall:rollback`。
> 全部经宿主执行通道调用宿主机 ufw（容器内没有 ufw）。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/status` | `ops:firewall:list` | 状态 + 规则清单 + 生存线 `FirewallStatus` |
| GET | `/guard` | `ops:firewall:list` | 生存线（SSH/面板端口、来源 IP、风险提示） |
| GET | `/raw` | `ops:firewall:list` | `ufw status numbered` 原文（排障用） |
| GET | `/guard/watchdog` | `ops:firewall:list` | 看门狗倒计时（未挂载返回 `data: null`） |
| POST | `/rule` | `ops:firewall:write` | 新增规则（高危审计） |
| DELETE | `/rule` | `ops:firewall:write` | 按编号删除（高危审计，需 fingerprint） |
| POST | `/enable` \| `/disable` | `ops:firewall:danger` | 全局开关（L3 + 看门狗） |
| POST | `/default-policy` | `ops:firewall:danger` | 设置默认策略（L3 + 看门狗） |
| POST | `/reload` | `ops:firewall:write` | 重载 |
| GET | `/changes?pageNum=&pageSize=` | `ops:firewall:list` | 变更历史（列表不含快照，详情才有） |
| GET | `/changes/{id}` | `ops:firewall:list` | 变更详情（含前后快照与 diff） |
| POST | `/changes/{id}/rollback` | `ops:firewall:rollback` | 回滚（需 `confirm: "ROLLBACK"`） |
| POST | `/guard/watchdog` | `ops:firewall:danger` | 手动挂看门狗（仅 `action=disable`） |
| POST | `/guard/confirm` | `ops:firewall:danger` | 保留变更（撤销看门狗） |

**规则模型**（动作与方向拆成两个字段，避免 `ALLOW IN` 里的方向混进来源列）：

```
FirewallRule {
  no           // ufw 编号，会随增删重排
  to, toKind   // 目标：any / port / range / multi / app
  action       // allow / deny / reject / limit
  direction    // in / out / fwd
  from, sourceKind // 来源：any / ip / cidr
  ipv6, comment, provenance  // 面板写入自动带 psm: 前缀 → provenance=panel
  deletable    // fail2ban 等外部托管规则为 false
  fingerprint  // to|action|from，删除时校验编号指向的仍是同一条
}
```

**写入**：`{ target: {kind, port, portEnd, ports}, protocol, action, source, comment, confirm }`。

**删除**：`{ no, fingerprint, confirm?, force? }`——

- 指纹与编号当前指向的规则不一致 → `5014`（防并发误删另一条）；
- 删除会**连同 IPv6 副本一并处理**：ufw 开启 IPv6 时一次 add 写入本体与 `(v6)` 两条，
  只删一条会留下看不见的半条规则；执行顺序为先删大编号再删小编号（ufw 每删一条重排编号）；
- 外部托管规则未带 `force` → `5016`；
- 无法还原成命令行的规则（应用名 profile 等）会记为 `rollbackable=0`，不可回滚。

**生存线（L3 二次确认，缺少或错误 → `5015`）**：

| 场景 | confirm 关键字 |
| ---- | ---- |
| 新增 deny/reject 覆盖 SSH 端口 | `SSH <port>` |
| 新增 deny/reject 覆盖面板端口 | `PANEL <port>` |
| 删除 SSH / 面板端口的 allow 规则 | 同上 |
| 启用防火墙且默认入站 deny 且 SSH 未放行 | `SSH <port>` |
| 停用防火墙 | `DISABLE` |
| 收紧默认入站策略 | `SSH <port>` |
| 回滚 | `ROLLBACK` |

**看门狗**：全局开关类操作生效的同时，在宿主机挂
`systemd-run --on-active=N` 一次性定时器；N 秒内无人调 `/guard/confirm` 即自动回滚，
防止「改完就失联、连撤销的机会都没有」。

### 4. 宿主执行通道 `ops/host`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/capability` | `ops:service:list` | 通道能力快照（可用性/模式/协议版本/缺失项/安装指引） |
| POST | `/probe` | `ops:service:list` | 主动重探（安装宿主代理后无需重启面板） |

服务管理、防火墙两个模块的系统命令全部经此通道在**宿主机**执行：
面板容器（eclipse-temurin:21-jre）里没有 systemctl / journalctl / ufw / df，
过去这些功能在容器内空转。通道不可用时（未安装代理 / socket 不可达）写接口返回 `5009`，
前端整页只读降级并给出安装指引。通道部署见 `documents/production-deployment.md` 第十一节。

### 5. Nginx 管理 `ops/nginx`

> 静态配置生成型：Web 录入意图 → 存库 → FreeMarker 渲染 conf → 写入 nginx 托管目录 →
> `nginx -t` 校验 → `reload` 生效；证书走内置 ACME（HTTP-01 / DNS-01 通配符）。
> 本质 = 配置 CRUD + 渲染器 + 进程管理器。可管理「可配置实例」或自动探测的本机 nginx。

**通道分工（关键）**：配置文件经 `/www` 挂载点写入（容器以 root 运行），
`nginx -t` / `-s reload` / certbot 经宿主通道 `psm-hostagent` 在宿主机以 root 执行
（容器内没有这些命令）。通道不可用时写接口返回 `6010` 类错误并前端只读降级。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/instance/list` | `ops:nginx:instance` | 实例列表 |
| GET | `/instance/detect` | `ops:nginx:instance` | 探测本机 nginx（不落库） |
| POST | `/instance` | `ops:nginx:instance` | 新建/保存实例（auto 探测或 manual 指定） |
| PUT | `/instance` | `ops:nginx:instance` | 更新实例 |
| DELETE | `/instance/{id}` | `ops:nginx:instance` | 删除实例（高危，`confirm` 域名） |
| PUT | `/instance/{id}/default` | `ops:nginx:instance` | 设为默认实例 |
| GET | `/status` | `ops:nginx:instance` | 运行态 + 版本 + 能力（含 certbot 版本、临期证书） |
| GET | `/existing` | `ops:nginx:instance` | 只读列出既有 vhost（宝塔等） |
| GET | `/site/preview` | `ops:nginx:site` | 渲染预览（不落盘） |
| GET | `/site/page` | `ops:nginx:site` | 站点分页 |
| POST | `/site` | `ops:nginx:site` | 新建站点（`nginx -t` + reload，记回滚快照） |
| PUT | `/site` | `ops:nginx:site` | 更新站点 |
| DELETE | `/site/{id}` | `ops:nginx:site` | 删除站点（高危，`confirm` 域名） |
| PUT | `/site/{id}/status/{0|1}` | `ops:nginx:site` | 启停站点 |
| GET | `/site/{id}/conf` | `ops:nginx:site` | 已渲染配置原文 |
| GET | `/upstream/page` | `ops:nginx:upstream` | 上游组分页 |
| POST | `/upstream` | `ops:nginx:upstream` | 新建上游组 |
| PUT | `/upstream` | `ops:nginx:upstream` | 更新上游组 |
| DELETE | `/upstream/{id}` | `ops:nginx:upstream` | 删除上游组（高危） |
| GET | `/stream/page` | `ops:nginx:stream` | 四层转发分页 |
| POST | `/stream` | `ops:nginx:stream` | 新建四层转发（TCP/UDP） |
| PUT | `/stream` | `ops:nginx:stream` | 更新四层转发 |
| DELETE | `/stream/{id}` | `ops:nginx:stream` | 删除四层转发（高危） |
| PUT | `/stream/{id}/status/{0|1}` | `ops:nginx:stream` | 启停四层转发 |
| GET | `/cert/page` | `ops:nginx:cert` | 证书分页（**修复项：曾因 reserved-word 别名 500**） |
| POST | `/cert/issue` | `ops:nginx:cert` | ACME 申请（mode=http01/dns01） |
| POST | `/cert/{id}/renew` | `ops:nginx:cert` | 续期 |
| GET | `/cert/{id}/status` | `ops:nginx:cert` | 证书状态（含到期日） |
| POST | `/cert/{id}/dns-verify` | `ops:nginx:cert` | DNS-01 二步：唤醒 certbot 完成签发 |
| POST | `/cert/upload` | `ops:nginx:cert` | 手动上传 PEM |
| DELETE | `/cert/{id}` | `ops:nginx:cert` | 删除证书（高危） |
| GET | `/log/list` | `ops:nginx:log` | 可查看日志清单 |
| GET | `/log/tail` | `ops:nginx:log` | 日志 tail |
| GET | `/change/page` | `ops:nginx:change` | 变更历史分页 |
| GET | `/change/{id}` | `ops:nginx:change` | 变更详情（前后快照 + diff） |
| POST | `/change/{id}/rollback` | `ops:nginx:change` | 回滚（高危，`confirm: ROLLBACK`） |
| POST | `/reload` | `ops:nginx:manage` | `nginx -t && nginx -s reload` |
| POST | `/test` | `ops:nginx:manage` | `nginx -t` 校验 |

**ACME**：HTTP-01 经 webroot（站点 `:80` 块内自动注入 `location ^~ /.well-known/acme-challenge/`）；
DNS-01 通配符两步流——首步 `issue` 返回需添加的 TXT 记录名/值（状态置 `pending`），用户添加后
调 `dns-verify` 唤醒 certbot 并轮询 `acmeStatus` 直至签发（最长约 5 分钟）。每日 03:30 调度扫描
临期（≤30 天）证书自动续期并刷新到期状态、临期/过期告警。**危险操作（删站/实例/证书/转发、回滚）
需 `confirm` 关键字二次确认，错误码 `6011`。**


### 6. 服务器配置管理 `ops/config`

> 非侵入式系统配置管理：Web 录入 → 存库（MongoDB）→ 渲染 drop-in 片段 → 经宿主通道生效
> → 权威校验 + 回读。每次破坏性动作留前后全文与行级 diff，支持按历史一键恢复、
> 支持「停止托管」纯净卸载。四类：`sysctl` / `limits` / `sshd` / `timesync`
> （时间同步的 provider 自适应 chrony | systemd-timesyncd）。

**核心语义**：配置项的 `itemValue` 为空 = **不托管该项**（本模块不向系统写这一行），
因此「清空值 → 一键生效」是合法操作，等价于让该项回到发行版默认值。

**非侵入落点**（发行版主配置永不修改）：

| 类别 | 托管文件（drop-in） | 即时生效动作 |
| ---- | ---- | ---- |
| sysctl | `/etc/sysctl.d/99-serverpanel.conf` | `sysctl --system` |
| limits | `/etc/security/limits.d/99-serverpanel.conf` | 无（仅对新会话/新进程生效） |
| sshd | `/etc/ssh/sshd_config.d/99-serverpanel.conf` | `systemctl restart sshd` |
| timesync | `/etc/systemd/timesyncd.conf.d/99-serverpanel.conf` 或 `/etc/chrony/conf.d/99-serverpanel.conf` | 重启 `systemd-timesyncd` / `chrony` |

**「一键生效」9 道闸门**：前端校验 → 服务端 schema/黑名单 → 宿主机 dry-run → 用户确认 →
备份 → 原子写 → 权威校验 → 生效 → 回读；**任一步失败自动回滚**（含写回备份并重新生效）。
L3 类别（sshd）额外要求键入后端下发的关键字。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/categories` | `ops:config:list` | 类别列表 + 宿主能力（可用性/托管路径/风险级/provider/托管项数） |
| GET | `/detect` | `ops:config:list` | 主动重探宿主能力（装好宿主代理后无需重启面板） |
| GET | `/category/{key}/items` | `ops:config:list` | 配置项（托管值 + 当前生效值 + 推荐值） |
| POST | `/category/{key}/item` | `ops:config:apply` | 新增配置项 |
| PUT | `/category/{key}/item/{itemKey}` | `ops:config:apply` | 修改配置项（值置空 = 不托管） |
| DELETE | `/category/{key}/item/{itemKey}` | `ops:config:apply` | 删除配置项（仅删录入，不改系统） |
| GET | `/category/{key}/preview` | `ops:config:list` | 预演：渲染全文 + diff + 宿主机 dry-run（**不落盘**） |
| POST | `/category/{key}/apply` | `ops:config:apply` | 一键生效（L3 需 `confirm`） |
| POST | `/category/{key}/unmanage` | `ops:config:apply` | 停止托管（删片段 + 全部备份并重新生效，L3 需 `confirm`） |
| GET | `/category/{key}/change/page` | `ops:config:list` | 变更历史分页（`key=all` = 跨类别总览） |
| GET | `/change/{id}` | `ops:config:list` | 变更详情（前后全文 + diff + 校验/生效输出 + 配置项快照） |
| POST | `/change/{id}/restore` | `ops:config:rollback` | 按历史恢复（`target=before/after`，L3 需 `confirm`） |

**宿主 op**（`ops/hostagent/hostagent.py`）：`sys.detect` / `sys.readManaged` / `sys.probe` /
`sys.validate` / `sys.apply` / `sys.rollback` / `sys.unmanage`。

**安全护栏**（服务端 `checkRules` 是唯一事实来源，前端不重复实现规则）：
sshd 不得同时关闭 `PasswordAuthentication` 与 `PubkeyAuthentication`；
timesync 至少保留一条 NTP 源；`vm.overcommit_memory ∈ {0,1,2}`；
`net.ipv4.ip_local_port_range` 需形如 `1024 65535` 且 `0 < 低 < 高 <= 65535`。
同一类别的生效/恢复/停止托管之间用 `ReentrantLock` 互斥（`6026`）。

**类别可用性判据**：sysctl/limits/sshd 由宿主 `sys.detect` 判定，
**依据是「主配置是否 Include 该 drop-in 目录」而不是「目录是否已存在」**
（`sshd_config.d` / `timesyncd.conf.d` 在不少发行版默认不存在，写入时按需创建）；
缺 `sysctl` / `sshd` 命令或缺少 `systemd-timesyncd`/`chrony` 时该类标为不可用并给出原因。

**存储与菜单**：MongoDB 三集合（配置类别 / 配置项 / 变更历史），Flyway `V9` 只建菜单与权限点
（菜单 `407`「服务器配置」→ `/ops/server-config`，按钮权限 `ops:config:apply`、`ops:config:rollback`）。

## 七、应用栈 `/api/v1/appstack`

### 1. Docker `appstack/docker`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/containers` | `appstack:docker:list` | 容器列表 `ContainerInfo[]` |
| POST | `/containers/{id}/{action}` | `appstack:docker:manage` | 动作（start/stop/restart/remove，高危审计） |
| GET | `/images` | `appstack:docker:list` | 镜像列表 `ImageInfo[]` |
| POST | `/images/pull` | `appstack:docker:manage` | 拉取镜像（高危审计） |

ContainerInfo：`id`、`name`、`image`、`state`、`status`、`ports`。
ImageInfo：`id`、`tag`、`size`、`created`。
PullImageBody：`image`（必填，≤200，如 `nginx:latest`）。

### 2. MySQL 数据库 `appstack/database`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=` | `appstack:database:list` | 库列表分页 |
| GET | `/charsets` | `appstack:database:list` | 支持字符集 `string[]` |
| POST | `/` | `appstack:database:add` | 建库（高危审计） |
| DELETE | `/{id}` | `appstack:database:delete` | 删库（高危审计） |
| POST | `/{id}/backup` | `appstack:database:backup` | 备份，返回 `data: 备份文件名` |
| POST | `/{id}/restore` | `appstack:database:backup` | 恢复（高危审计） |

DatabaseBody：`dbName`（必填，≤32，仅 `[a-zA-Z0-9_]`）、`charset`（必填，utf8mb4/utf8/latin1/gbk）、`remark`。

建库响应 `data`（DatabaseCreateResult，**密码仅返回一次**）：

```json
{ "dbName": "myapp", "username": "myapp", "password": "Xk9f2LpQ", "charset": "utf8mb4" }
```

RestoreBody：`backupFile`（必填，仅允许 `[a-zA-Z0-9_.-].sql`，防路径穿越）。

### 3. 定时任务 `appstack/job`

参考 xxl-job 的「调度中心 / 执行器」分层与「调度日志双段」模型，但**借形不借体**：
不做注册中心与心跳，不做在线编码（GLUE）。宿主侧零新增 op —— SHELL 复用 `host.exec`，
SERVICE 复用 `service.action`。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=&handler=&status=` | `appstack:job:list` | 任务分页 |
| GET | `/stats` | `appstack:job:list` | 顶部统计（含调度器开关、宿主通道可用性） |
| GET | `/handlers` | `appstack:job:list` | 4 类处理器的表单 schema（前端据此**动态渲染**，新增处理器不改前端） |
| GET | `/commands` | `appstack:job:list` | 命令白名单 + 宿主是否真的装了该命令（`available` / `path`） |
| GET | `/options` | `appstack:job:list` | 下拉源：路由策略 / 阻塞策略 / 内置任务清单 / 各项上限 |
| POST | `/cron/validate` | `appstack:job:list` | 校验 cron（合法性 + 相邻间隔下限），返回 `valid` / `message` |
| GET | `/{id}` | `appstack:job:list` | 任务详情 |
| GET | `/{id}/next-times` | `appstack:job:list` | 未来若干次触发时间 |
| POST | `/` | `appstack:job:save` | 新建（高危审计） |
| PUT | `/{id}` | `appstack:job:save` | 编辑（高危审计） |
| DELETE | `/{id}` | `appstack:job:delete` | 删除（需 confirm `DELETE JOB <name>`；**日志保留**，任务名已快照） |
| POST | `/{id}/copy` | `appstack:job:save` | 复制为新任务（名字自动去重加后缀） |
| POST | `/{id}/enable` | `appstack:job:toggle` | 启用 |
| POST | `/{id}/disable` | `appstack:job:toggle` | 停用 |
| POST | `/{id}/run` | `appstack:job:run` | 立即执行；被丢弃时返回 **6040** 与真实原因 |
| POST | `/{id}/stop` | `appstack:job:stop` | 停止（需 confirm `STOP JOB <name>`） |
| GET | `/executor/list` | `appstack:job:list` | 执行器列表 |
| POST | `/executor` | `appstack:job:executor` | 新建外部执行器 |
| PUT | `/executor/{id}` | `appstack:job:executor` | 编辑执行器 |
| DELETE | `/executor/{id}` | `appstack:job:executor` | 删除执行器（需 confirm `DELETE EXECUTOR <appName>`；内置执行器返回 6035） |
| POST | `/executor/{id}/test` | `appstack:job:executor` | 连通性探测（**不写审计**：探测不是变更） |

JobBody：`jobName`（必填）、`jobDesc`、`handler`（SHELL / HTTP / SERVICE / INTERNAL）、
`handlerParam`（**对象**，落库为 varchar，读回是 JSON 字符串）、`cronExpr`、
`executorId`、`routeStrategy`（FIRST / ROUND / RANDOM / FAILOVER）、
`blockStrategy`（SERIAL / DISCARD_LATER / COVER_EARLY）、`timeoutSec`、`retryCount`、
`status`（`0` 停用 / `1` 启用）。注意字段名是 `cronExpr` / `timeoutSec`，不是 `cron` / `timeoutSeconds`。

4 类 handler 的 `handlerParam`：

| handler | 参数 | 约束 |
| ------- | ---- | ---- |
| `SHELL` | `command`、`args[]`、`env{}` | 命令名必须在白名单内且不含路径分隔符；**只接受 argv 数组，绝不拼 shell 字符串**；非白名单返回 **6036** |
| `HTTP` | `method`、`url`、`headers{}`、`body`、`expectStatus` | URL 仅 `http/https`，黑名单元数据地址（`169.254.169.254` 等），不跟随重定向，响应上限 1MB |
| `SERVICE` | `unit`、`action` | action 必须落在宿主代理允许的动作集内；受保护单元 + 破坏性动作需 confirm `APPLY <unit>`（**6039**） |
| `INTERNAL` | `task`、`params{}` | `task` 取自 `/options` 的 `internalTasks`（内置 SPI 注册，未知取值返回 **6033**） |

阻塞策略与确认关键字：

| 策略 | 语义 |
| ---- | ---- |
| `SERIAL` | 等待前次结束（上限 `timeoutSec`）后执行 |
| `DISCARD_LATER` | 前次未结束则本轮直接丢弃，记 `DISCARDED` + `trigger_code=500` |
| `COVER_EARLY` | 覆盖前次（日志标 `KILLED`）后执行本轮 |

确认关键字（服务端逐字校验，非单一硬编码串）：`DELETE JOB <name>`、`STOP JOB <name>`、
`DELETE EXECUTOR <appName>`、`CLEAR LOG`、`APPLY <unit>`。

### 4. 定时任务日志 `appstack/job-log`

日志沿用 xxl-job 的**双段式**结构，`trigger_*` 段记「调度是否派发出去」，
`handle_*` 段记「执行结果如何」。两段分开的价值在于：派发失败（`trigger_code=500`）
与执行失败（`handle_code=500`）是两类完全不同的故障，必须能分辨。

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?jobId=&jobName=&status=&handler=&triggerType=&beginTime=&endTime=` | `appstack:joblog:list` | 日志分页 |
| GET | `/statistics` | `appstack:joblog:list` | 总数 / 成功 / 失败 / 执行中 / 已丢弃 / 已终止 / 平均耗时 / 保留天数 |
| GET | `/retention` | `appstack:joblog:list` | 日志保留天数 |
| GET | `/{id}` | `appstack:joblog:list` | 日志详情 |
| GET | `/{id}/output` | `appstack:joblog:list` | 执行输出全文（列表页不下发，避免大字段拖慢分页） |
| DELETE | `/clear` | `appstack:joblog:clear` | 清理（需 confirm `CLEAR LOG`；**必须至少给一个条件** —— 任务 / 时间范围 / 状态，否则 6033） |

ClearLogBody：`jobId`、`beforeTime`（ISO 日期时间）、`status`、`confirm`。

`status` 取值：`RUNNING` / `SUCCESS` / `FAILED` / `TIMEOUT` / `DISCARDED` / `KILLED`。

> **`KILLED` 的边界**：面板侧执行线程可被真实中断（HTTP / 内置任务的日志停在 `KILLED`），
> 但 SHELL / SERVICE 的真实执行体是宿主机上的子进程，宿主代理没有「按 id 终止某次 host.exec」
> 的 op，因此进程可能继续跑到自己的超时。**UI 必须明示这一点**，不要把它说成「已杀死」。

## 八、前端接入要点（Vben Admin 5）

### 1. 请求客户端

`src/api/request.ts` 的 `requestClient` 已配置三层响应拦截器：

1. `defaultResponseInterceptor`：按 `code=0` 判定成功，剥离 `data` 返回（`responseReturn: 'data'`）；
2. `authenticateResponseInterceptor`：401 时自动调 `/auth/refresh` 续期一次，失败则登出；
3. `errorMessageResponseInterceptor`：非 0 code 统一 `message.error(msg)`。

请求拦截器自动附加 `Authorization: Bearer <token>` 与 `Accept-Language`。

### 2. 登录与动态路由时序

1. 登录：`POST /auth/login` → 存 `accessToken`；
2. 拉信息：`GET /user/info` → 用户信息与角色；
3. 拉权限码：`GET /auth/codes` → 按钮级控制（`hasAccessByCodes`）；
4. 拉路由：`GET /menu/all` → `generateRoutesByBackend` 生成动态路由；
5. 偏好同步：路由守卫登录态建立后异步 `GET /user/preference` 应用云端偏好（不阻塞首屏），设置面板变更后防抖 `PUT` 全量保存（见 `src/utils/preference-sync.ts`）；
6. 登出：清空本地态 → `POST /auth/logout`。

### 3. 新模块接入步骤

1. 后端新增 Controller：`@RequestMapping("/api/v1/<module>/...")`，写操作加 `@Audit` 与 `@SaCheckPermission`；
2. 数据库 `sys_menu` 增加菜单/按钮记录（含 `perms`），角色重新授权；
3. 前端 `src/api/<module>.ts` 封装请求函数（`requestClient.get/post/...`）；
4. 页面挂到已授权路由组件路径；按钮用 `hasAccessByCodes(['<module>:xxx'])` 控制显隐。

### 4. 特殊响应处理

| 场景 | 处理 |
| ---- | ---- |
| 文件下载 | 走 `blob` 响应，前端从 `Content-Disposition` 解析文件名（该接口不走 R 包装） |
| 文件上传 | `FormData`，`dir` 与 `files` 字段 |
| 建库密码 | 仅创建响应返回一次，前端需即时展示/提示用户保存 |
| WebSocket | URL query 携带 token，`/ws/monitor`，每 5s 一帧 |
