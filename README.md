# ServerPanel 个人服务器管理系统

类似宝塔面板的单机服务器管理面板：**Java 21 + Spring Boot 4.1** 后端，**Vue 3 + Vben Admin 5** 前端，前后端一体化部署（单 fat jar 同时承载页面与 API）。

## 功能模块

| 模块 | 说明 |
| ---- | ---- |
| 系统管理 | 用户 / 角色 / 菜单 / 字典 / 参数配置 / 操作审计 / 登录日志 |
| 实时监控 | CPU / 内存 / 磁盘 / 网络，WebSocket 实时曲线 + 历史聚合 |
| 文件管理 | 白名单根目录、树表浏览、上传下载、在线编辑、压缩解压、回收站 |
| 运维工具 | 进程管理、systemd 服务、计划任务、防火墙（ufw/firewalld） |
| 应用栈 | Docker 容器/镜像、Nginx 网站（反代/静态/HTTPS）、MySQL 建库/账号/备份恢复 |

## 目录结构

```
├── backend/                 # Spring Boot 多模块（boot → system/monitor/file/ops/appstack → framework → common）
├── frontend/                # Vben Admin 5（web-antd）
├── docker/                  # MySQL/Redis 开发环境（docker-compose.dev.yml）
└── scripts/
    ├── build.sh             # 生产构建：前端产物合成进后端单 jar
    └── install.sh           # systemd 安装与启动
```

## 一、开发环境

### 1. 基础设施

```bash
docker compose -f docker-compose.dev.yml up -d
```

启动 MySQL 8.4（库 `server_panel`，账号 `panel/Panel@123456`）与 Redis 7。

### 2. 后端

```bash
# 需要 JDK 21 / Maven 3.9
cd backend
mvn -s .mvn/settings.xml -pl server-boot -am spring-boot:run
```

启动成功日志：Flyway 执行 `V1__init.sql`、Druid 连接池初始化。默认监听 `8080`。

### 3. 前端

```bash
cd frontend
pnpm install
pnpm dev  # 或 cd apps/web-antd && pnpm dev
```

开发模式前端走 Vite 代理，`/api` 转发到后端 `8080`，`/ws` 转发 WebSocket。

## 二、登录与默认账号

浏览器访问 `http://localhost:8080/`，使用 `admin / Admin@123` 登录，请登录后立即修改密码。

## 三、生产部署

### 1. 依赖

- JDK 21
- MySQL 8.x（面板库由 Flyway 自动建表，需授权一个 `panel` 账号）
- Redis 6+
- 可选：Nginx（网站管理）、Docker（容器管理）、`mysqldump`（数据库备份）

> Nginx 站点配置目录默认为 `/etc/nginx/panel.d`，需在主配置中加
> `include /etc/nginx/panel.d/*.conf;` 并保证 `nginx -t` 可执行。

### 2. 构建单 jar

```bash
./scripts/build.sh
# 产物：backend/server-boot/target/serverpanel.jar
```

### 3. 安装为系统服务

```bash
sudo ./scripts/install.sh /path/to/serverpanel.jar
```

安装脚本会写入 `/etc/systemd/system/serverpanel.service` 并 `enable --now`。
可用环境变量（安装前 export 即可生效）：`SERVER_PORT`、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`REDIS_HOST/PORT/PASSWORD`、`PANEL_MYSQL_ADMIN_USER/PASSWORD`（面板代管 MySQL 的管理账号）、`PANEL_FILE_ROOTS`、`PANEL_NGINX_CONF_DIR`。

常用运维：

```bash
systemctl status serverpanel
journalctl -u serverpanel -f
```

### 4. HTTPS 建议

生产建议由外层 Nginx/Caddy 反代 `8080` 并终结 TLS；也可在面板「网站管理」中为已部署站点配置证书。

## 四、安全设计要点

- 命令执行：仅白名单命令，argv 数组直传（无 shell 拼接），敏感信息（如 MySQL 密码）经环境变量传递
- 文件管理：根目录白名单 + `toRealPath()` 归一化校验，防路径穿越与软链逃逸
- 数据库管理：库名/账号名严格正则白名单，密码服务端随机生成、仅返回一次
- 认证：Sa-Token，Header Bearer 令牌，高危操作（删文件/删库/重启服务等）计入操作审计

## 五、验证冒烟清单

1. `docker compose -f docker-compose.dev.yml up -d` 后启动后端，日志出现 Flyway 迁移成功
2. 登录接口：`curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}'`
3. 带 token 访问 `/api/v1/monitor/overview`、`/api/v1/file/list?path=/www`
4. 前端登录后：仪表盘实时曲线、系统管理 CRUD、文件/运维/应用栈各页面冒烟
5. 接口文档：`/swagger-ui.html`
