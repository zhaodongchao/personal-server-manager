# 04 宿主执行通道 psm-hostagent

## 1. 为什么需要宿主通道

ServerPanel 面板本身跑在 Docker 容器里（`eclipse-temurin:21-jre`），容器内**没有** `systemctl` / `journalctl` / `ufw` / `df` 等宿主机工具。因此「服务管理」「防火墙管理」「Nginx 生效」「服务器配置生效」这类操作在容器内是空转的。

解决方式：引入 **psm-hostagent** —— 部署在宿主机上的单文件 Python systemd 服务，作为唯一执行通道，面板把命令经它代理到宿主机 root 执行。

## 2. 整体拓扑

```
后端 (server-boot, 容器内)
  │  HostChannelService.call(op, args, label, timeoutSec)
  ▼
HostAgentExecutor (server-framework, 容器内)
  │  读共享密钥 + 写单行 JSON 请求 + 读响应（AF_UNIX SocketChannel）
  ▼
/run/psm-hostagent/agent.sock  (AF_UNIX，不暴露 TCP)
  ▼
hostagent.py (psm-hostagent, 宿主机 root, systemd)
  │  密钥鉴权 → op 白名单 → 逐 op 参数校验 → subprocess argv 数组执行
  ▼
宿主机系统命令（systemctl / ufw / nginx -t / certbot / sysctl ...）
```

- **安全边界**：共享密钥认证、46 个操作白名单、逐操作参数校验、子进程一律 argv 数组（无 shell 拼接）。
- **后端能力快照**：`/ops/host/capability`（OS/kernel/tools/missing/firewallBackend）。
- **降级**：通道不可用时写接口抛 `HOST_CHANNEL_UNAVAILABLE(5009)`，前端整页只读降级（`HostChannelBanner.vue` 提示安装指引）。

## 3. Python 宿主代理（ops/hostagent/hostagent.py）

单文件实现。关键参数（[hostagent.py:52-68](file:///workspace/ops/hostagent/hostagent.py)）：

```python
SECRET_FILE = "/run/psm-hostagent/secret"      # 共享密钥文件
SOCKET_PATH = "/run/psm-hostagent/agent.sock"  # AF_UNIX socket
RUN_DIR     = "/run/psm-hostagent"
STATE_DIR   = "/var/lib/psm-hostagent"          # 状态/备份目录
```

### 请求处理入口（[hostagent.py:1063-1111](file:///workspace/ops/hostagent/hostagent.py)）
1. 读取共享密钥并校验请求里的 secret。
2. 查找已注册 op。
3. 逐 op 校验 args。
4. 执行 handler，记录结构化日志。
5. 返回统一 JSON 响应。

### 命令执行（[hostagent.py:151-195](file:///workspace/ops/hostagent/hostagent.py)）
`subprocess.Popen(argv, shell=False)`：带安全环境变量、超时、进程终止、stdout/stderr 截断与 duration 统计。

### 操作白名单分组

代理注册了约 46 个 op，按前缀分组（op 名即鉴权 + 分发的 key）：

| 前缀 | 覆盖能力 |
| --- | --- |
| `host.exec`/`host.probe` | 通用执行（受限白名单命令）、能力探测 |
| `service.*` | systemd 单元列表、状态、cat、isActive、日志、启停动作、daemonReload（unit 名/动作/信号校验） |
| `firewall.*` | 防火墙状态、规则增删、编号删除、启用、默认策略、reload/version（端口/源地址/协议/评论校验） |
| `nginx.*` | `nginx -t` / `-s reload`、ACME（certbot HTTP-01 / DNS-01 两步流） |
| `sysctl.*` / `limits.*` / `sshd.*` / `timesync.*` | 服务器配置 drop-in 生效 |
| 其他 | df/磁盘、进程等系统命令 |

> 完整 op 清单与实际校验逻辑以 `hostagent.py` 中注册为准（`OPERATIONS`/各 `handle_*` handler）。

### 体系文件
- [psm-hostagent.service](file:///workspace/ops/hostagent/psm-hostagent.service)：systemd 单元，`ExecStart=hostagent.py serve`，`RuntimeDirectoryPreserve=yes` 避免容器挂载点失效。
- [install.sh](file:///workspace/ops/hostagent/install.sh)：生成/保留共享密钥、复制 service 文件、`daemon-reload/enable/restart`，并自检 socket + `host.probe` 验证链路，列出容器需挂载目录。
- [uninstall.sh](file:///workspace/ops/hostagent/uninstall.sh)：卸载。

## 4. 后端接入（server-framework / server-ops）

### 通道实现 [HostAgentExecutor.java](file:///workspace/backend/server-framework/src/main/java/com/serverpanel/framework/command/HostAgentExecutor.java)
- 实现 `HostExecutor` 抽象，与 `CommandExecutor`（容器内）二选一。
- 注入：socket path、secret file、connect timeout、default/max timeout。
- `probeCapability()`：检查密钥/套接字 → `host.probe` → 解析 OS/kernel/tools/missing/firewallBackend，缓存 capability。
- 主流程 `call()`：读 secret → 组单行 JSON 请求 → 发送（超时控制）→ 解析 `HostResult` → 处理 timeout/interrupt/io-error。
- AF_UNIX 读写：`UnixDomainSocketAddress` + `SocketChannel`，写一行 JSON，读到换行并限制响应大小。

### 门面 [HostChannelService.java](file:///workspace/backend/server-ops/src/main/java/com/serverpanel/ops/service/HostChannelService.java)
- `require()`：通道不可用直接抛 `HOST_CHANNEL_UNAVAILABLE`。
- `call(op, args, label, timeoutSec)`：统一调用宿主 op；通道级失败抛 5009，命令级失败抛 500。

### 运维模块使用方
服务/防火墙/Nginx/服务器配置四个模块统一走 `hostChannel.call("service.*" / "nginx.*" ...)`。

## 5. 新增宿主能力约定

新增「需要宿主机能力」的功能时，必须同时：
1. 在 `hostagent.py` 白名单里加 op（并遵守「<op 注册位于 `if __name__ == '__main__'` 守卫之前」的坑位规则）。
2. 在 `documents/api-spec.md` 第六节登记契约。
3. 后端在前端 `HostChannelBanner` 生态中保持能力快照同步。