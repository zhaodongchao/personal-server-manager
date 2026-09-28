# 06 运行与构建

## 1. 开发环境（本地）

### 1.1 基础设施（MySQL + Redis）
在仓库根目录启动依赖：

```bash
docker compose -f docker-compose.dev.yml up -d
```

- MySQL 8.4：库 `server_panel`，账号 `panel/Panel@123456`
- Redis 7

### 1.2 后端（JDK 21 / Maven 3.9）

```bash
cd backend
mvn -s .mvn/settings.xml -pl server-boot -am spring-boot:run
```

- 默认监听 `:8080`，profile `dev`（`SPRING_PROFILES_ACTIVE` 可覆盖）。
- 启动成功标志：Flyway 执行迁移、Druid 连接池初始化。
- 常用：
  - 编译：`./mvnw compile`
  - 测试：`./mvnw test`
  - 打包：`./mvnw package`（或 `-DskipTests`）
  - 格式化校验/应用：`./mvnw spotless:check` / `./mvnw spotless:apply`

### 1.3 前端（Node ≥ 24 / pnpm 11）

```bash
cd frontend
pnpm install
pnpm dev          # 或 cd apps/web-antd && pnpm dev
```

- 开发期 Vite 代理：`/api` → `localhost:8080`，`/ws` → WebSocket（见 [vite.config.ts](file:///workspace/frontend/apps/web-antd/vite.config.ts)）。
- 常用命令：
  - 类型检查：`pnpm check:type`
  - 构建：`pnpm build:antd`（全量 `pnpm build`）
  - 代码检查：`pnpm lint` / `pnpm check`
  - 格式化：`pnpm format`
  - 单元测试：`pnpm test:unit`

## 2. 访问与默认账号

浏览器访问 `http://localhost:8080/`，使用 `admin / Admin@123` 登录，请登录后立即修改。

接口文档：`/swagger-ui.html`（springdoc OpenAPI）。

## 3. 生产构建（单 jar，前后端一体化）

```bash
./scripts/build.sh
# 产物：backend/server-boot/target/serverpanel.jar
```

`build.sh` 流程：先构建前端静态产物 → 合成进后端 `src/main/resources/static` → 打包后端 fat jar。

## 4. 安装为系统服务

```bash
sudo ./scripts/install.sh /path/to/serverpanel.jar
```

- 写入 `/etc/systemd/system/serverpanel.service` 并 `enable --now`。
- 支持环境变量（安装前 `export`）：
  - `SERVER_PORT`
  - `MYSQL_HOST/PORT/DB/USER/PASSWORD`
  - `REDIS_HOST/PORT/PASSWORD`
  - `PANEL_MYSQL_ADMIN_USER/PASSWORD`（面板代管 MySQL 管理账号）
  - `PANEL_FILE_ROOTS`

常用运维：

```bash
systemctl status serverpanel
journalctl -u serverpanel -f
```

## 5. 宿主通道（生产可选项，运维能力必需）

「服务管理 / 防火墙管理 / Nginx 生效 / 服务器配置」依赖宿主机执行能力。若不需要这些模块可跳过；需要时安装：

```bash
sudo ./ops/hostagent/install.sh
```

安装后自检 socket 与 `host.probe`（详见 [04 宿主通道](04-host-channel.md)）。通道不可用时后端写接口返回 `5009`，前端整页只读降级并显示安装指引。

## 6. 安全设计要点

- 命令执行：白名单 + argv 数组直传（无 shell 拼接），敏感信息（MySQL 密码）经环境变量传递。
- 文件管理：根目录白名单 + `toRealPath()` 归一化，防路径穿越与软链逃逸。
- 数据库管理：库名/账号名严格正则白名单，密码服务端随机生成、仅返回一次。
- 认证：Sa-Token Bearer 令牌；高危操作（删文件/删库/重启服务）计入操作审计。
- 敏感配置经环境变量注入，禁止明文凭据入库。

## 7. 验证冒烟清单

1. 基础设施启动后启动后端，日志出现 Flyway 迁移成功。
2. 登录：
   ```bash
   curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"Admin@123"}'
   ```
3. 带 token 访问 `/api/v1/monitor/overview`、`/api/v1/file/list?path=/www`。
4. 前端登录后：仪表盘实时曲线、系统管理 CRUD、文件/运维/应用栈各页面冒烟。
5. Swagger：`/swagger-ui.html`。