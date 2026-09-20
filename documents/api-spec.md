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
| 1xxx | 认证 | `1001` 账号或密码错误、`1002` 失败锁定、`1003` 停用、`1004` 原密码错 |
| 2xxx | 系统管理 | `2001` 用户已存在、`2002` 角色已存在、`2003` 内置数据、`2004` 不能删自己、`2005` 有子菜单、`2006` 角色在用、`2007/2008` 字典重复、`2009` 参数键重复 |
| 3xxx | 监控 | `3001` 采集器未就绪 |
| 4xxx | 文件 | `4001` 路径越权、`4002` 目标已存在、`4003` 源路径不存在、`4004` 根目录受限、`4005` 文件过大、`4006` 非文本、`4007` 压缩格式不支持、`4008` 回收站记录失效、`4009` 权限非法、`4010` 目录非空 |
| 5xxx | 运维 | `5001` 命令不在白名单、`5002` 超时、`5003` 进程不存在、`5004` 服务不可管理、`5005` cron 非法、`5006` 任务不存在、`5007` 无可用防火墙、`5008` 规则不存在 |
| 6xxx | 应用栈 | `6001` Docker 不可用、`6002` 资源不存在、`6003` 域名已存在、`6004` Nginx 不可用、`6005` 配置校验失败、`6006` MySQL 管理连接不可用、`6007` 标识符非法、`6008` 库已存在、`6009` 库不存在 |

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
| GET | `/audit-log/page?operator=&module=` | `system:audit:list` | 操作审计分页 |
| GET | `/login-log/page?username=&status=` | `system:loginlog:list` | 登录日志分页 |

## 四、监控 `/api/v1/monitor`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/overview` | `dashboard:view` | 系统概览 + 最新帧 |
| GET | `/history?minutes=60` | `dashboard:view` | 历史帧（1-60 分钟，钳制） |

`/overview` 响应 `data`（MonitorOverview）：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `hostname` / `os` / `kernel` / `cpuModel` | string | 主机信息 |
| `cpuPhysicalCores` / `cpuLogicalCores` | number | 核数 |
| `disks[]` | array | 真实挂载的文件系统 `{mount, source(源设备,如 /dev/mapper/vg0-root), fsType, vg(所属 LVM 卷组,非 LVM 为空), totalBytes, usableBytes, usage}`；伪文件系统（proc/sysfs/overlay 等）与 loop/ram 源不返回 |
| `physicalDisks[]` | array | 真实物理磁盘（排除 loop/dm/ram 等虚拟设备）`{name, model, serial, sizeBytes, partitions[]{name,mount,sizeBytes,type(文件系统类型),vg(作为 PV 时所属卷组)}}` |
| `deviceMappers[]` | array | Device Mapper 设备（LVM 逻辑卷映射 / dm-* 等）`{name(/dev/mapper/vg0-root), vg, sizeBytes, fsType, mount}` |
| `lvm` | object | LVM 信息 `{physicalVolumes[]{name,vg,sizeBytes,freeBytes}, volumeGroups[]{name,pvCount,lvCount,sizeBytes,freeBytes}, logicalVolumes[]{name(设备映射名 vg0-root),vg,sizeBytes,fsType,mount}}`；非 LVM 环境各列表为空 |
| `interfaces[]` | array | `{name, ipv4, speed}` |
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
| POST | `/recycle/empty` | `file:delete` | 清空回收站 |

> 安全约束：所有路径必须位于 `PANEL_FILE_ROOTS` 白名单内，后端做 `toRealPath()` 归一化校验。

## 六、运维工具 `/api/v1/ops`

### 1. 进程管理 `ops/process`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/list?keyword=` | `ops:process:list` | 进程列表 `ProcessInfo[]` |
| POST | `/{pid}/kill` | `ops:process:kill` | 结束进程（高危审计） |

ProcessInfo：`pid`(number)、`user`、`cpu`、`mem`、`stat`、`elapsed`、`cmd`。

### 2. systemd 服务管理 `ops/service`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/list?keyword=` | `ops:service:list` | 服务列表 `ServiceInfo[]` |
| GET | `/{name}` | `ops:service:list` | 服务状态文本 |
| POST | `/{name}/{action}` | `ops:service:manage` | 动作（start/stop/restart/reload/enable/disable，高危审计） |

ServiceInfo：`name`、`load`、`active`、`sub`、`description`、`enabled`。

### 3. 计划任务 `ops/cron`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=` | `ops:cron:list` | 任务分页 |
| POST | `/` | `ops:cron:add` | 新增 |
| PUT | `/` | `ops:cron:edit` | 编辑 |
| DELETE | `/{id}` | `ops:cron:delete` | 删除（高危审计） |
| POST | `/{id}/run` | `ops:cron:run` | 立即执行，返回 `data: logId`（高危审计） |
| GET | `/{id}/logs` | `ops:cron:list` | 执行日志分页 |

CronJobBody：`id`(编辑时必填)、`name`（必填）、`cronExpr`（必填）、`command`（必填，白名单命令+参数）、`timeoutSec`（默认 300）、`status`（1/0）、`remark`。

### 4. 防火墙 `ops/firewall`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/status` | `ops:firewall:list` | 状态 + 规则列表 `FirewallStatus` |
| POST | `/rule` | `ops:firewall:write` | 新增规则（高危审计） |
| DELETE | `/rule` | `ops:firewall:write` | 删除规则（高危审计，body 同新增） |

FirewallStatus：`backend`（ufw/firewalld/none）、`active`(boolean)、`rules[]`（`{id, port, action, source}`）。

FirewallRuleBody：`port`(number, 1-65535)、`protocol`（tcp/udp）、`action`（allow/deny）、`source`（可选，如 `192.168.1.0/24`）。

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

### 2. Nginx 网站 `appstack/website`

| 方法 | 路径 | 权限 | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/page?keyword=` | `appstack:website:list` | 站点分页 |
| GET | `/nginx-status` | `appstack:website:list` | Nginx 可用性 `data: true/false` |
| GET | `/{id}/conf` | `appstack:website:list` | 站点配置内容 |
| POST | `/` | `appstack:website:add` | 新增 |
| PUT | `/` | `appstack:website:edit` | 编辑（高危审计） |
| PUT | `/{id}/status/{status}` | `appstack:website:edit` | 启停（1/0，高危审计） |
| DELETE | `/{id}` | `appstack:website:delete` | 删除（高危审计） |

WebsiteBody：

| 字段 | 类型 | 校验 |
| ---- | ---- | ---- |
| `id` | number | 编辑时必填 |
| `domain` | string | 必填，域名格式 |
| `siteName` | string | 必填，≤60 |
| `siteType` | string | 必填，`proxy` / `static` |
| `upstream` | string | proxy 必填，`http(s)://` 开头 |
| `staticRoot` | string | static 必填（须在文件白名单内） |
| `sslEnabled` | number | 0/1 |
| `certPath` / `keyPath` | string | 启用 SSL 时必填 |
| `remark` | string | 可选 |

### 3. MySQL 数据库 `appstack/database`

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
