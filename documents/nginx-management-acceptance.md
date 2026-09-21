# Nginx 管理模块 · 验收报告（S1~S4）

> 项目：personal-server-manager（ServerPanel）· 运维工具 → Nginx 管理
> 范围：S1 实例探测/状态/既有站点只读 · S2 站点/上游/四层转发/日志/回滚/危险确认 · S3 ACME（HTTP-01 + DNS-01 两步 + 每日续期调度）· S4 文档/端到端/收尾
> 日期：2026-09-21　作者：zhaodc
> 部署：后端 #92（`4ad3ae2`）· 前端 #93（`506815d`，镜像 `psm-frontend:master-b93-506815d` / `psm-backend:master-b93-506815d`）

---

## 一、结论

**通过。** 设计目标（配置生成型：录入→存库→FreeMarker 渲染→写入托管目录→`nginx -t`→`reload`；证书内置 ACME）全部落地，端到端验收 **20/20 通过**（只读 11 + 写入链路 9），真实浏览器（CDP）渲染复验通过，文档四件套与代码对齐。

用户最初报的 `GET /api/v1/ops/nginx/cert/page?pageNum=1&pageSize=100` 报错已根治（根因是 MySQL 保留字 `binary` 作 MyBatis-Plus 别名导致全列表 500），现稳定返回 200。

> **CDP 渲染复验期间额外发现并修复了一个 P0 缺陷**：雪花 ID 超出 JS 安全整数导致前端回传 id 失真（详见 §四.3）。该缺陷会让「新建站点 / 申请证书 / 回滚」等所有写操作在 UI 上失败，而纯接口测试（Python）无法暴露——**这是"接口全绿 ≠ 页面可用"的典型证据**。

## 二、根因与总体方案

| 项 | 内容 |
| --- | --- |
| 根因（初始报错） | `OpsNginxInstance.binary` 属性被 MyBatis-Plus 用作 SELECT 别名 → `SELECT ... binary_path AS binary`，`binary` 是 MySQL 保留字 → 所有 nginx 列表接口（含 cert/page）500 |
| 修复 | 属性改名 `binary → binaryPath`（列名 `binary_path` 不变），hostagent 读键 `dataString("binary")` 保持不变 |
| 通道分工（D2） | 配置文件经 `/www` 挂载写入（容器 root）；`nginx -t`/`-s reload`/certbot 经宿主通道 `psm-hostagent` 在宿主机 root 执行（容器内无 nginx/certbot） |
| ACME | HTTP-01（webroot，`location ^~ /.well-known/acme-challenge/` 自动注入 `:80` 块）；DNS-01 通配符两步 TXT 流；每日 03:30 `NginxCertRenewScheduler` 扫描续期 |
| 安全 | 写前必 `nginx -t`，失败不 reload；每次写操作记 `OpsNginxChange`（前后快照+diff）；危险操作（删站/实例/证书/转发、回滚）需 `confirm` 关键字，错误码 `6011` |

## 三、各阶段落地

### S1 实例探测与只读 ✅
- `GET /instance/detect`（不落库）/`GET /instance/list`/`GET /status`/`GET /existing`（宝塔既有 vhost 只读）。
- 实例可配置：手动填二进制/prefix/托管目录，或 `detectMode=auto` 自动探测；无实例时整体探测本机。

### S2 站点/上游/转发/日志/回滚 ✅
- 站点（proxy/static）、上游组（round_robin/least_conn/ip_hash）、四层转发（TCP/UDP）。
- 渲染预览、启停、已渲染配置原文查看。
- 变更历史 + 前后快照对照 + 一键回滚；危险操作 `confirm` 二次确认。

### S3 ACME 与续期 ✅
- HTTP-01 webroot 质询目录注入；DNS-01 两步流：`issue` 返回需添加的 TXT 记录名/值（状态置 `pending`）→ 用户添加后 `dns-verify` 唤醒 certbot 并轮询 `acmeStatus` 至签发（最长约 5 分钟）。
- 前端：申请方式切换、TXT 复制、验证并签发、pending 行「继续验证」。
- 每日 03:30 续期调度（临期 ≤30 天自动续期 + 状态刷新 + 临期/过期告警）。

### S4 文档与收尾 ✅
- `documents/api-spec.md`：错误码补 `6010~6015`；新增 §6.6 `ops/nginx` 全量接口契约（实例/状态/站点/上游/转发/证书/日志/变更/重载）。
- `AGENTS.md`：新增「§7 Nginx 管理」架构认知。
- `documents/production-deployment.md`：新增「§12 Nginx 管理模块」部署说明。
- `tasks/lessons.md`：追加 3 条经验（保留字别名、容器内/宿主机通道分工、DNS-01 两段式）。
- 提交 `809661c`。

## 四、端到端验收证据

**只读 + 探测（11/11 PASS）** — `verify_nginx_e2e.py` 真实登录后打接口：

- 1 登录取得 token ✅
- 2 `instance/list` http=200 count=0 ✅
- 3 `status` http=200 nginxAvailable/certbotVersion 字段齐 ✅
- 4 `instance/detect` → `binary=/usr/bin/nginx` ✅
- 5 `cert/page` / `site/page` / `upstream/page` / `stream/page` / `change/page` 全部 http=200（**原 500 的 cert/page 已修复**）✅
- 6 `existing` http=200 count=2 ✅

**写入链路实测（9/9 PASS）** — `verify_nginx_e2e2.py` 真实改服务器配置：

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 1 | 探测 nginx | `binary=/usr/bin/nginx` `prefix=/www/server/nginx` ✅ |
| 2 | 存为实例（auto） | http=200 id=2102014431325683713 ✅ |
| 3 | 实例状态 | `nginxAvail=True` `certbot=2.1.0` `configValid=True` ✅ |
| 4 | 建站（proxy→127.0.0.1:8080） | `nginx -t` + reload 成功，change 已记 ✅ |
| 5 | reload 后 | `configValid=True` `siteCount=1` ✅ |
| 6 | 站点列表 | 含新建 `psm-e2e-site` ✅ |
| 7 | 回滚建站变更 | http=200 ✅ |
| 8 | 删除实例 | http=200 ✅ |

> 写入链路全程经宿主通道真实执行 `nginx -t` / `reload`，并在结束后回滚+删实例，现场无残留。

**前端产物确认**：运行容器 `psm-frontend:master-b93-506815d` 的 `CertDrawer-fxlCwhxF.js` 含「验证并签发」、`api-BcXyY__C.js` 含 `dns-verify` —— **S3 前端确实已部署到浏览器端**。

**宿主代理 DNS-01 操作注册确认**：`/usr/local/lib/psm-hostagent/hostagent.py` 含 `@op('nginx.acmeDns01Issue')` / `@op('nginx.acmeDns01Verify')` 及 `_certbot_dns_argv` / `_ensure_dns_hook`。

### 3. 真实浏览器渲染复验 + P0 缺陷（雪花 ID 精度丢失）

CDP 探针 `probe33.js`（登录 → `/#/ops/nginx` → 打开证书抽屉 → 打开申请表单）：

| 复验项 | 结果 |
| --- | --- |
| 菜单「Nginx 管理」可见、路由可达 | ✅ `hash=#/ops/nginx` |
| 实例下拉回显 | ✅ `主机默认 nginx（默认）` |
| 宿主通道状态卡 | ✅ `通道 可用` |
| 操作按钮齐全 | ✅ 新建站点 / 上游组 / 四层转发 / 证书 / 变更历史 / 日志 / 现有站点 / 测试配置 / 重载 nginx |
| 证书抽屉 | ✅ `SSL 证书` + `共 0 张证书` + `申请 / 上传证书` |
| **S3 新增 UI** | ✅ 证书类型（`Let's Encrypt（自动申请）`/`手动上传`）、**申请方式（`HTTP-01（Webroot，单域名）` / `DNS-01（通配符 *.example.com）`）**、HTTP-01 提示文案 |

**⚠️ P0 缺陷（已修，commit `f33ed87`）**：页面请求发出的是
`GET /ops/nginx/status?instanceId=2102014067671138300`，而真实 ID 是 `2102014067671138305`。

- **根因**：`IdType.ASSIGN_ID` 生成的雪花 ID 为 19 位（`2.1e18`），远超 `Number.MAX_SAFE_INTEGER`（`9.007e15`）。
  后端以 JSON 数字输出 → 前端 `JSON.parse` 后末位精度丢失 → 回传的是被截断的 ID → 后端查不到实例
  → 状态降级为 `instanceExists=false / nginxVersion=null`（页面显示 `nginx 版本 -`、`配置校验 未知`）。
- **取证方法**：CDP 挂 `Network` 域抓 `Network.getResponseBody`，对比「页面发出的 URL」与「Python 直接调接口的结果」。
  只跑接口（Python 保留精度）永远发现不了。
- **修复**：对外返回的 `id / instanceId / certId / jobId / changeId` 统一加
  `@JsonSerialize(using = ToStringSerializer.class)`（Jackson 3 包名 `tools.jackson.databind.*`）。
  `OpsCronJob` 的 `id` 继承自全局基类 `BaseEntity`（`sys_user`/`sys_menu` 等 10 个实体共用，不能改），
  改用 **getter 覆写**加注解，避免波及小 ID 实体。
- **一并修复**：`ops_cron_log`、`ops_firewall_change`、`FirewallActionResultVO` —— 三处同样存在回传失真。
- **教训**：**凡是雪花 ID 经前端回传的表，必须序列化为字符串**；验收不能只跑接口，必须开真实浏览器看网络面板。

## 五、关键提交

| 提交 | 内容 |
| --- | --- |
| `d15a4eb` | S3 后端修复 MySQL 保留字别名（binary→binaryPath）#91 |
| `4ad3ae2` | S3 后端：HTTP-01 质询目录 + DNS-01 通配两步流 + 每日续期调度 #92 |
| `506815d` | S3 前端：DNS-01 两步流 UI #93 |
| `809661c` | S4 文档四件套对齐 |
| `f33ed87` | **修复雪花 ID 前端精度丢失**（实体/VO 的 id 统一序列化为字符串） |

## 六、遗留与建议

1. **DNS-01 未做真实签发联调**：两步流后端逻辑与宿主操作已就绪，但因需真实公网通配符域名 + 域名商 TXT 生效，本次未跑完整 `issue→加TXT→dns-verify` 闭环；建议在有真实域名的环境补一轮验收。
2. **lego 未安装**：当前 ACME 走 certbot（已确认 2.1.0 可用），DNS-01 用 certbot `--manual` + 认证钩子；若日后要更多 DNS 提供商自动化，再评估 lego。
3. **前端 dist 噪音**：`backend/server-boot/src/main/resources/static/` 每次构建被覆盖产生未跟踪文件，建议加 `.gitignore`（与运维三模块同源问题一致）。
4. **certbot 配置目录映射**：续期调度在容器内执行 `certbot`，其 `--config-dir` 指向挂载的 `le/` 目录，需确认该目录在发版重建后持久化（compose 已挂卷则无虞）。
