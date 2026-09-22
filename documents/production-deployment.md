# ServerPanel 生产环境部署指南

本文档面向全新 Linux 服务器，覆盖从零安装到生产可用的完整流程：基础设施准备、单 jar 构建、systemd 服务安装、反向代理与 HTTPS、日常运维与故障排查。

## 一、部署架构

```
用户浏览器
    │ HTTPS :443
    ▼
Nginx / Caddy（反代 + TLS 终结）
    │ http :8080（本机回环）
    ▼
serverpanel.jar（Spring Boot 单进程）
    │        ├── MySQL 8.x（业务库，Flyway 自动建表）
    ├────────┼── Redis 6+（会话 / 监控环形缓存）
    │        ├── 可选：Docker（容器管理，unix socket）
    │        └── 可选：Nginx（面板托管网站配置，/etc/nginx/panel.d）
    └── 文件系统白名单目录（/www、/srv、/var/www）
```

要点：

- 面板与被管理服务器为**同一台机器**（单机面板模式），不需要 Agent。
- 后端自带前端页面（fat jar 内嵌 static），无需单独部署前端。
- 建议面板服务只监听回环地址，由外层 Nginx/Caddy 提供 HTTPS，避免明文 8080 暴露。

## 二、环境准备

| 软件 | 版本要求 | 说明 |
| ---- | ---- | ---- |
| 操作系统 | CentOS 7+/Ubuntu 20.04+/Debian 11+ | x86_64 / aarch64 |
| JDK | 21（推荐 21 LTS） | 编译与运行都需要 |
| Maven | 3.9+ | 仅构建需要，运行机可不装 |
| MySQL | 8.x | 业务数据库 |
| Redis | 6+ | 会话与监控数据 |
| Node.js + pnpm | Node 20+ / pnpm 9+ | 仅构建需要 |
| 可选 | Docker / Nginx / mysqldump | 对应面板功能模块 |

### 1. 安装 JDK 21

```bash
# Ubuntu/Debian
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk

# CentOS/RHEL
sudo yum install -y java-21-openjdk

java -version   # 确认输出为 21.x
```

### 2. 安装并初始化 MySQL 8

```bash
# Ubuntu/Debian
sudo apt-get install -y mysql-server
sudo systemctl enable --now mysql
```

创建业务账号并授权（**务必修改密码**）：

```sql
-- 用 root 登录 MySQL
CREATE DATABASE IF NOT EXISTS server_panel
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'panel'@'localhost' IDENTIFIED BY 'Panel@123456';
CREATE USER 'panel'@'127.0.0.1' IDENTIFIED BY 'Panel@123456';
GRANT ALL PRIVILEGES ON server_panel.* TO 'panel'@'localhost';
GRANT ALL PRIVILEGES ON server_panel.* TO 'panel'@'127.0.0.1';
-- Flyway 12 初始化需要只读 performance_schema（否则迁移报 SELECT command denied）
GRANT SELECT ON performance_schema.user_variables_by_thread TO 'panel'@'localhost';
GRANT SELECT ON performance_schema.user_variables_by_thread TO 'panel'@'127.0.0.1';
FLUSH PRIVILEGES;
```

> 表结构由 Flyway 首次启动时自动创建（`V1__init.sql`），无需手工导入。

### 3. 安装并启动 Redis

```bash
# Ubuntu/Debian
sudo apt-get install -y redis-server
sudo systemctl enable --now redis-server

# 如需密码（推荐）
sudo redis-cli CONFIG SET requirepass 'YourRedisPass'
```

## 三、构建单 jar

在**开发机或构建机**上执行（需 JDK 21、Maven 3.9、Node 20+、pnpm）：

```bash
cd /path/to/serverpanel
./scripts/build.sh
# 产物：backend/server-boot/target/serverpanel.jar（含前端页面，约 80~100MB）
```

将 jar 传到生产机：

```bash
scp backend/server-boot/target/serverpanel.jar root@<server>:/opt/serverpanel/serverpanel.jar
```

## 四、安装为系统服务

### 1. 准备目录

```bash
sudo mkdir -p /opt/serverpanel
# 将 serverpanel.jar 放入 /opt/serverpanel/
```

### 2. 配置环境变量并安装

安装脚本读取当前 shell 的环境变量写入 systemd 单元，**先 export 再执行**：

```bash
export SERVER_PORT=8080                 # 面板监听端口（默认 8080）
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DB=server_panel
export MYSQL_USER=panel
export MYSQL_PASSWORD='Panel@123456'    # 与第二步授权的业务账号一致
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD='YourRedisPass'   # 无密码则留空
# 面板代管 MySQL 的管理账号（用于建库/备份/恢复，生产建议专用高权限账号）
export PANEL_MYSQL_ADMIN_USER=root
export PANEL_MYSQL_ADMIN_PASSWORD='RootPassword'
# 文件管理根目录白名单（逗号分隔）
export PANEL_FILE_ROOTS='/www,/srv,/var/www'
# Nginx 站点配置目录
export PANEL_NGINX_CONF_DIR='/etc/nginx/panel.d'

cd /path/to/serverpanel
sudo ./scripts/install.sh /opt/serverpanel/serverpanel.jar
```

### 3. 验证启动

```bash
systemctl status serverpanel
journalctl -u serverpanel -f          # 跟踪启动日志
```

预期日志：Flyway 迁移成功 → Druid 连接池初始化 → `Started ServerPanelApplication`。

### 4. 首次登录

浏览器访问 `http://<host>:8080/`，默认账号 **admin / Admin@123**，**登录后立即修改密码**。

## 五、环境变量总览

| 变量 | 默认值 | 说明 |
| ---- | ---- | ---- |
| `SERVER_PORT` | `8080` | 面板 HTTP 监听端口 |
| `MYSQL_HOST` / `MYSQL_PORT` | `localhost` / `3306` | 业务库地址 |
| `MYSQL_DB` | `server_panel` | 业务库名 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `panel` / `Panel@123456` | 业务账号 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `REDIS_DB` | `0` | Redis 逻辑库 |
| `PANEL_MYSQL_ADMIN_HOST/PORT` | `localhost` / `3306` | MySQL 管理连接 |
| `PANEL_MYSQL_ADMIN_USER/PASSWORD` | `root` / 空 | 管理账号（建库/备份/恢复） |
| `PANEL_FILE_ROOTS` | `/www,/srv,/var/www` | 文件管理根目录白名单 |
| `PANEL_TRASH_DIR` | `/var/serverpanel/trash` | 文件回收站目录 |
| `PANEL_NGINX_CONF_DIR` | `/etc/nginx/panel.d` | 面板托管 Nginx 站点配置目录 |
| `PANEL_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker Engine 连接 |
| `HOST_SYSROOT` | 空 | 容器部署时宿主机根挂载路径（见下文 Docker 部署说明） |
| `SERVER_ADDRESS` | `0.0.0.0` | 面板监听地址（生产建议 `127.0.0.1`） |

> 修改环境变量后：`sudo systemctl restart serverpanel`。

### Docker 部署的宿主机磁盘可见性

面板以 Docker 容器部署时，容器默认隔离宿主机块设备与挂载表，监控页的物理磁盘 / Device Mapper / LVM / 文件系统将只能看到容器自身视角。需将宿主机根以只读方式递归挂载进容器并设置 `HOST_SYSROOT`：

```bash
docker run -d --name serverpanel \
  -p 8080:8080 \
  -v /:/host:ro \
  -e HOST_SYSROOT=/host \
  -e MYSQL_HOST=host.docker.internal \
  ... serverpanel:latest
```

- `-v /:/host:ro`：宿主机根只读挂载，面板经其读取宿主机 `/proc/mounts` 与 `/sys` 块设备（磁盘 / LVM / 文件系统拓扑）。
- `HOST_SYSROOT=/host`：指定挂载路径；未配置时自动探测 `/host`、`/hostfs`、`/mnt/host`。
- LVM 明细（PV/VG 空间统计）依赖容器内可用的 `pvs/vgs/lvs`，不可用时自动回退 lsblk 拓扑推导。
- 裸机 / systemd 部署无需配置（`host-sysroot` 留空）。

## 六、反向代理与 HTTPS（推荐）

面板默认监听 8080 明文端口。生产建议将 `SERVER_ADDRESS=127.0.0.1` 并通过外层 Nginx 提供 HTTPS：

```bash
sudo apt-get install -y nginx
```

站点配置 `/etc/nginx/conf.d/serverpanel.conf`：

```nginx
server {
    listen 443 ssl http2;
    server_name panel.example.com;

    ssl_certificate     /etc/nginx/ssl/panel.crt;      # 用 certbot 申请
    ssl_certificate_key /etc/nginx/ssl/panel.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket（监控实时曲线）
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}

server {
    listen 80;
    server_name panel.example.com;
    return 301 https://$host$request_uri;
}
```

签发证书（Let's Encrypt）：

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d panel.example.com
```

## 七、Nginx 网站管理（面板托管站点）

如果使用面板的「网站管理」功能：

1. 面板生成站点配置到 `/etc/nginx/panel.d/`（默认，可用 `PANEL_NGINX_CONF_DIR` 调整）。
2. 在主配置中引入该目录：

```bash
# /etc/nginx/nginx.conf 的 http {} 块内追加
include /etc/nginx/panel.d/*.conf;
```

3. 确认 `nginx -t` 可执行且权限允许面板重载（面板以 root 运行 systemd 服务，默认满足）。

## 八、日常运维

### 1. 服务管理

```bash
systemctl status serverpanel     # 状态
journalctl -u serverpanel -f     # 实时日志
journalctl -u serverpanel -n 200 # 最近 200 行
sudo systemctl restart serverpanel
```

### 2. 数据备份

```bash
# 业务库全量备份（面板数据）
mysqldump -u root -p server_panel > /var/backups/server_panel_$(date +%F).sql

# 备份 jar 与站点配置
cp -r /etc/nginx/panel.d /var/backups/panel.d.bak
```

建议配合 crontab 每日备份并异地存放。

### 3. 版本升级

```bash
# 1. 构建新 jar 并替换
scp serverpanel.jar root@<server>:/opt/serverpanel/serverpanel.jar
# 2. 重启（Flyway 自动执行增量迁移）
sudo systemctl restart serverpanel
```

> 升级前先备份数据库。Flyway 迁移失败时应用不会启动，`journalctl -u serverpanel -n 200` 查看具体错误。

### 4. 卸载

```bash
sudo systemctl disable --now serverpanel
sudo rm -f /etc/systemd/system/serverpanel.service
sudo systemctl daemon-reload
sudo rm -rf /opt/serverpanel            # 按需删除（含数据）
```

## 九、常见问题排查

| 现象 | 排查 |
| ---- | ---- |
| 服务启动失败，日志含 Flyway 错误 | 检查 `panel` 账号是否已授予 `performance_schema.user_variables_by_thread` 只读权限 |
| `Access denied for user 'panel'` | 核对 `MYSQL_USER/PASSWORD`，确认账号可从本机连接 |
| 8080 无法访问 | `sudo ss -tlnp | grep 8080` 确认监听；防火墙放行 `firewall-cmd --add-port=8080/tcp` |
| 登录提示密码错误 | 确认默认账号 admin / Admin@123；多次失败会触发 15 分钟锁定 |
| 监控曲线不更新 | 检查 WebSocket：确认反代配置了 `Upgrade/Connection` 头且 `/ws` 路径放行 |
| 文件管理报"源路径不存在" | 确认目标目录在 `PANEL_FILE_ROOTS` 白名单内且物理存在 |
| 网站管理 `nginx -t` 失败 | 检查 `PANEL_NGINX_CONF_DIR` 是否已被主配置 include，证书文件路径是否正确 |

## 十一、宿主执行通道（psm-hostagent）

面板容器里没有 systemctl / journalctl / ufw 等命令，服务管理、计划管理、防火墙三个模块
需要在**宿主机**上执行系统命令。为此在宿主机上安装一个极小的 systemd 服务 `psm-hostagent`：

- 单文件 Python 3.11，监听 **AF_UNIX** socket `/run/psm-hostagent/agent.sock`（0660，不暴露 TCP）；
- 共享密钥认证（密钥 0400），46 个操作白名单 + 每操作参数校验；
- 子进程一律 argv 数组（不经过 shell），杜绝注入；
- `RuntimeDirectoryPreserve=yes` 保证重启后 socket inode 稳定。

安装（面板页面「运维工具」里会给出与当前后端协议匹配的安装指引，也可手动执行）：

```bash
# 仓库内 ops/hostagent/ 提供安装脚本与 hostagent.py
sudo bash ops/hostagent/install.sh
# 校验
systemctl is-active psm-hostagent
sudo ss -xlp | grep agent.sock
```

安装完成后回到面板点「重新探测」即可，无需重启面板容器。
升级面板后若后端协议版本变化，需重新执行安装脚本（脚本会覆盖并重启服务）。

## 十二、Nginx 管理模块（运维工具）

面板「运维工具 → Nginx 管理」是配置生成型管理器，依赖宿主执行通道（`psm-hostagent` 第十一节）：

- 站点/上游/转发/证书的**配置写入**经 `/www` 挂载点（容器以 root 运行，可直接写 nginx 托管目录）；
- **`nginx -t` / `nginx -s reload` / certbot** 经宿主通道在宿主机以 root 执行；
- 首次使用建议在「实例」里点「探测」自动识别本机 nginx，或手动填写二进制路径与托管目录；
- ACME 证书默认申请到宿主机的 certbot 配置目录（容器挂载映射），续期由每日 03:30 调度自动执行；
- 所有写操作均生成可回滚快照，删站/删证书/回滚需二次确认关键字。

## 十三、服务器配置管理模块（运维工具）

面板「运维工具 → 服务器配置」在线管理四类系统配置（内核参数 / 资源限制 / SSH / 时间同步）。
**不修改发行版主配置**，只写发行版之外的 drop-in 片段：

| 类别 | 落点 | 生效方式 |
| ---- | ---- | ---- |
| 内核参数 | `/etc/sysctl.d/99-serverpanel.conf` | `sysctl --system`（即时并随启动保持） |
| 资源限制 | `/etc/security/limits.d/99-serverpanel.conf` | 无即时动作，**仅对新会话/新进程生效** |
| SSH | `/etc/ssh/sshd_config.d/99-serverpanel.conf` | `systemctl restart sshd` |
| 时间同步 | `/etc/systemd/timesyncd.conf.d/` 或 `/etc/chrony/conf.d/` | 重启对应服务 |

前提条件与注意事项：

- 依赖宿主执行通道（第十一节）——`sys.*` 七个 op 在宿主机以 root 执行（容器内没有
  `sysctl` / `sshd` / `systemctl`）；
- **SSH 类别**要求主配置 `/etc/ssh/sshd_config` 含 `Include /etc/ssh/sshd_config.d/*.conf`；
  该**目录本身可以不存在**，首次生效时按需创建。若主配置缺少这行 Include，面板会把该类别
  标为「不可用」并给出原因，避免「写了却不生效」；
- 数据存 **MongoDB**（类别 / 配置项 / 变更历史三集合），Flyway `V9` 只负责菜单与权限点；
- 每次生效前自动备份托管片段（`.psm.bak.<时间戳>`）；校验或生效失败会**自动回滚**
  （写回备份并重新生效）；单次生效会记录前后全文、行级 diff、校验/生效输出与配置项快照；
- 「停止托管」= 删除托管片段与全部备份并重新生效（回到发行版原状），面板里的配置项录入保留；
  若重新生效失败，会自动写回原托管内容并再次生效，避免留下「片段已删 + 服务未重载」的半残状态；
- L3 类别（SSH）的生效 / 停止托管 / 按历史恢复都要求键入关键字 `APPLY sshd`，
  防止改错 SSH 参数把自己锁在门外。

## 十、上线前安全检查清单

- [ ] 已修改默认密码 `admin / Admin@123`
- [ ] MySQL 业务账号与 Redis 使用强密码
- [ ] `SERVER_ADDRESS` 设置为 `127.0.0.1`，只通过 HTTPS 反代暴露
- [ ] `PANEL_MYSQL_ADMIN_PASSWORD` 使用独立高权限账号而非 root 明文密码
- [ ] 文件根目录白名单仅开放必要路径
- [ ] 开启系统防火墙并放行 443/80，关闭 8080 对公网
- [ ] 高危操作（删文件/删库/重启服务）已确认会写入操作审计
