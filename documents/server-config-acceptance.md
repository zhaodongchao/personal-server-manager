# 服务器配置管理模块 · 验收报告（S0~S4）

> 项目：personal-server-manager（ServerPanel）· 运维工具 → 服务器配置
> 范围：S0 设计/宿主能力 · S1 后端（MongoDB 三集合 + 一键生效 9 道闸门 + 变更历史与恢复 + 停止托管）·
> S2 前端（四类别页 + 预演弹窗 + 变更历史/危险确认）· S3 安全加固与端到端 · S4 文档与真实浏览器复验
> 日期：2026-09-22　作者：zhaodc
> 部署：Jenkins **#98** `master-6bfb8ec`（后端 + 前端均构建，`psm-backend` / `psm-frontend` healthy）
> 过程版本：后端 #95 `ee01345`（S0.2+S1）· 后端 #96 `c84ee9b`（停止托管）·
> 前端 #97 `73af149`（S2）· 前端 #98 `6bfb8ec`（历史默认全部类别）

---

## 一、结论

**通过。** 设计目标（MongoDB 存配置、前端增删改查、**一键生效严格校验**、每次生效前的详细日志与
配置历史、按配置历史一键恢复）全部落地，并额外实现了设计里承诺的「停止托管（纯净卸载）」。

三层验证全绿：

| 层 | 内容 | 结果 |
| --- | --- | --- |
| 宿主 op 层 | `sys.*` 七个 op 直连 socket 实测（detect / readManaged / probe / validate / apply / rollback / unmanage） | 通过 |
| 接口层 | 停止托管闭环 **43/43**、sshd（L3）受控真实链路 **48/48** | **91/91 通过** |
| 页面层 | CDP 真实浏览器 + Network 域取证 **30/30**，含 3 张截图 | 通过 |

本轮发现并修复 **3 个真实缺陷**（宿主可用性判据写错、停止托管非失败安全、变更历史默认过滤过窄），
另有 **3 项为验证脚本自身问题**（详见 §五）——其中 2 项正是把「正确的系统行为」误报成缺陷，
说明「断言假设」本身也需要被验证。

---

## 二、设计要点

| 项 | 内容 |
| --- | --- |
| 覆盖面 | 四类：`sysctl`（内核参数）/ `limits`（资源限制）/ `sshd`（SSH）/ `timesync`（时间同步，provider 自适应 chrony ｜ systemd-timesyncd） |
| 非侵入落点 | 只写发行版之外的 drop-in：`/etc/sysctl.d/99-serverpanel.conf`、`/etc/security/limits.d/99-serverpanel.conf`、`/etc/ssh/sshd_config.d/99-serverpanel.conf`、timesyncd `conf.d` / chrony `conf.d` 的 `99-serverpanel.conf`；**发行版主配置永不修改** |
| 核心语义 | **空值 = 不托管**（不写这一行 = 系统沿用发行版默认值），因此「清空值 → 生效」是合法操作 |
| 一键生效 9 道闸门 | 前端校验 → 服务端 schema/黑名单 → 宿主 dry-run → 用户确认 → 备份 → 原子写 → 权威校验 → 生效 → 回读；**任一步失败自动回滚**（写回备份并重新生效） |
| 可追溯 | 每次动作记一条变更历史：前后全文 + 行级 diff + 校验输出 + 生效输出 + 宿主阶段明细 + 配置项前后快照 + 操作人/IP/耗时；失败（含被拦截）同样留痕 |
| 可恢复 | 按历史一键恢复（`target=before/after`，走与生效同一套闸门）；「停止托管」删除片段与全部备份并重新生效，回到发行版原状 |
| 自锁护栏 | L3 类别（sshd）的生效 / 停止托管 / 按历史恢复都必须键入后端下发的关键字 `APPLY sshd`；sshd 不得同时关闭 `PasswordAuthentication` 与 `PubkeyAuthentication` |
| 并发 | 同一类别的生效/恢复/停止托管用 `ReentrantLock` 互斥（`6026`） |
| 存储 | MongoDB 三集合（配置类别 / 配置项 / 变更历史）；Flyway `V9` 只建菜单与权限点（菜单 `407`，权限 `ops:config:list/apply/rollback`） |
| 宿主通道 | 七个 op：`sys.detect` / `sys.readManaged` / `sys.probe` / `sys.validate` / `sys.apply` / `sys.rollback` / `sys.unmanage`（宿主代理 op 总数 28 → **46**） |

---

## 三、各阶段落地

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| S0 | 设计文档 `psm/design/server-config-design.md`（v2）+ 宿主 `sys.*` op + 本机能力探测 | ✅ |
| S1 | 后端：MongoDB 三实体 + DTO + `ServerConfigService`（9 道闸门 / 历史 / 恢复 / 停止托管）+ `ServerConfigSeeder`（4 类 48 项）+ `ServerConfigController`（12 端点）+ `V9` 迁移 + 错误码 6020~6026 | ✅ |
| S2 | 前端：`api/server-config.ts` + 页面四类别页签 + 配置项增删改 + 预演弹窗（dry-run/diff/关键字）+ 变更历史抽屉（详情 + 按历史恢复）+ 危险确认弹窗 | ✅ |
| S3 | 安全加固与端到端：并发互斥、L3 关键字护栏、黑名单规则、停止托管失败安全、三层端到端 | ✅ |
| S4 | 文档四件套对齐（api-spec / production-deployment / AGENTS / lessons）+ CDP 真实浏览器复验 + 本报告 | ✅ |

---

## 四、验收证据

### 1. 宿主 op 层（直连 `/run/psm-hostagent/agent.sock`）

| op | 验证点 | 结果 |
| --- | --- | --- |
| `sys.detect` | 四类可用性 + 托管文件路径 + provider（本机 timesyncd）；sshd 修复后可用 | ✅ |
| `sys.readManaged` | 托管内容 + 备份列表（`.psm.bak.<ts>`） | ✅ |
| `sys.probe` | `sysctl -n` 逐键 / `sshd -T` / limits 合并（大小写不敏感，`-` 类型=软硬同值）/ timesyncd ini | ✅ |
| `sys.validate` | 片段级 dry-run（`sshd -t -f <tmp>`、sysctl 键存在性）；非法行被拒 | ✅ |
| `sys.apply` | 预演→备份→原子写→权威校验→生效→回读；**仅在托管文件已存在时才产生备份**（首次生效无可备份内容，符合预期） | ✅ |
| `sys.rollback` | 按备份恢复并重新生效 | ✅ |
| `sys.unmanage` | 删除片段 + 全部备份 + 空目录；幂等；未知类别 `bad-request` | ✅ |

### 2. 接口层（`/api/v1/ops/config`）

**停止托管闭环 43/43**（`/tmp/cdp/verify_unmanage_e2e.py`）：类别能力 → 配置项 → 预演（无规则错误 +
dry-run 通过）→ 生效（写入片段）→ **二次生效留备份**（`stages.backup.created=true`）→ 停止托管
（片段与备份全删、`managed=false`、**配置项保留不丢录入**）→ 幂等复跑 → 变更历史（`UNMANAGE/SUCCESS` +
详情含 `removed` + 保留 8 项快照）→ 回归（生效→按历史恢复）→ 收尾清理 → 错误路径
（L3 缺关键字 `6024`、未知类别 `6020`）。

**sshd（L3）受控真实链路 48/48**（`/tmp/cdp/verify_sshd_e2e.py`）。为什么敢在 sshd 上打真实生效：
三项最危险的配置（`Port` / `PermitRootLogin` / `PasswordAuthentication`）**默认不托管**，
实际写入的只有 `PubkeyAuthentication yes`、`UseDNS no`、`GSSAPIAuthentication no`、
`MaxAuthTries 3`、`ClientAliveInterval 300`、`ClientAliveCountMax 2`，均不影响「能否登录」，
且链路上有 `sshd -t` 权威校验 + 失败自动回滚 + 重启保留既有连接。覆盖：

- `sshd` 类别可用（**修复前整类灰显**，见 §五 D1）；
- L3 关键字护栏：无关键字 / 关键字错误均 `6024` 且**不落盘**；
- 黑名单：同时关闭密码与公钥认证被 `6025` 拦下且不落盘，回滚配置项后预演恢复通过；
- 真实生效：片段写入 0644（非组/其他可写，不被 sshd 拒绝）、`systemctl is-active sshd` 仍为 `active`；
- 回读：`sshd -T` 反映 `MaxAuthTries=3` / `ClientAliveInterval=300` / `ClientAliveCountMax=2`；
- **停止托管后回到发行版默认**：`MaxAuthTries 3→6`、`ClientAliveInterval 300→0`、
  `ClientAliveCountMax 2→3`（三项都与发行版默认不同，是有区分度的证据）；
- 失败留痕：历史里 6 条 `APPLY/FAILED`（关键字与黑名单拦截各次）。

### 3. 页面层（CDP 真实浏览器 + Network 域）

`/tmp/cdp/probe35.js` → **30/30 PASS**，产物 `/tmp/cdp/server-config.png`、
`server-config-preview.png`、`server-config-history.png`。

- 侧边菜单出现「服务器配置」，且 `/menu/all` 响应体含 `/ops/server-config` 节点（DOM + 数据双证）；
- 页面标题、四个类别页签（含已托管项数徽标）、宿主通道状态条、类别信息卡（风险级/托管状态/托管文件/
  生效说明）均正确渲染；
- 操作按钮齐全：新增配置项 / 刷新生效值 / 重新探测 / 变更历史 / 一键生效 / 停止托管；
- 配置项表格 25 行，列含「参数名 / 托管值 / 当前生效值 / 推荐值 / 说明」（空值显示「不托管」标签）；
- **预演弹窗**：标题「一键生效预演 · 内核参数」，展示 dry-run 结论 + 行级 diff + 将写入全文；
- **变更历史抽屉**：20 条记录、表头含时间/类别/操作/结果/操作人/来源 IP/耗时/操作；
- 页面实际发出的 `/ops/config/*` 请求全部 200 且响应体 `code=0`；**无加载失败请求、无控制台异常**。

> 截图里中文显示为方块，是探针容器缺 CJK 字体所致；DOM 文本断言（`内核参数` / `资源限制` /
> `SSH 服务` / `时间同步`、卡片文案）已单独证明页面上的中文正常。

### 4. 数据面

| 类别 | 可用 | 风险 | provider | 配置项 | 已托管 | 托管文件 |
| --- | --- | --- | --- | --- | --- | --- |
| sysctl | ✅ | L2 | - | 25 | 20 | `/etc/sysctl.d/99-serverpanel.conf` |
| limits | ✅ | L1 | - | 8 | 8 | `/etc/security/limits.d/99-serverpanel.conf` |
| sshd | ✅ | L3 | - | 10 | 6 | `/etc/ssh/sshd_config.d/99-serverpanel.conf` |
| timesync | ✅ | L1 | timesyncd | 5 | 5 | `/etc/systemd/timesyncd.conf.d/99-serverpanel.conf` |

变更历史共 **41** 条：`UNMANAGE/SUCCESS` 16、`APPLY/SUCCESS` 10、`APPLY/FAILED` 8、
`UNMANAGE/FAILED` 3、`RESTORE/SUCCESS` 4 —— 说明成功路径、拦截路径与恢复路径都真实走过且都留了痕。

> 种子的安全策略可复核：`sysctl` 25 项里 20 项预置（仅安全增益项），5 项留空；
> `sshd` 10 项里 6 项预置，`Port` / `PermitRootLogin` / `PasswordAuthentication` / `X11Forwarding`
> 留空并要求用户显式设置——**风险项默认不托管**，避免「装上就改安全策略」。

---

## 五、本轮发现并修复的缺陷

### 产品缺陷（3 项，均已修复并复验）

**D1 · 宿主可用性判据写错，SSH 类别被整类灰显（影响功能可用性）**
现象：页面 SSH 页签带「不可用」标记，所有按钮禁用，原因显示「目录不存在: /etc/ssh/sshd_config.d」。
事实：`/etc/ssh/sshd_config` 第 12 行**确实**有 `Include /etc/ssh/sshd_config.d/*.conf`，
只是该目录在 Debian 12 上默认不存在——而写入阶段本来就会 `os.makedirs` 按需创建。
根因：把「目录是否已存在」当成了可用性门槛。
修复：`_sys_availability()` 改为解析主配置里**未被注释**的 `Include` 指令，只有「主配置根本没 Include」
才算不可用，且原因文案说清「需先手工启用该 Include」。修复后四类全部 `available=true`。

**D2 · 「停止托管」不是失败安全的（有数据不可逆风险）**
现象：原实现先删片段**和全部备份**，再重新生效；若 `systemctl restart` 出错，就留下
「片段已删 + 备份已删 + 服务仍跑旧配置」的半残状态，且已无内容可回退。
修复：删除前把片段内容读进内存；重新生效失败时写回原内容并再跑一次生效，返回 `unmanaged: false`，
后端据此记一条 `FAILED`（而不是假成功）。符合「破坏性操作的前置备份要等新状态确认可用后才能丢」。

**D3 · 变更历史默认按当前类别过滤，跨类别无记录时显示空列表（可用性误导）**
现象：从「内核参数」页签打开变更历史看到「暂无变更记录」，而实际系统里有 41 条记录
（只是不在该类别下）。
修复：抽屉默认「全部类别」，当前类别一键可切。变更历史是全局审计轨迹，默认口径不应把绝大多数记录藏起来。

### 验证脚本自身问题（3 项，非产品缺陷）

**T1 · 弱断言**：unmanage 用例统计「备份文件」时用了前缀匹配，把托管文件本身也算进去，
于是「生效已留备份」在无备份时也会通过。改成只匹配 `.psm.bak.` 后暴露了正确语义：
**首次生效时托管文件不存在，本来就没有可备份内容**；真正的备份链路在第二次生效时验证。

**T2 · 断言假设错误（把正确行为误报为缺陷）**：sshd 停止托管后断言 `UseDNS` 回到 `yes`，
实测仍为 `no` 并一度被当成回滚失败。查 `sshd_config(5)`：**这份 OpenSSH 构建的 `UseDNS`
默认值就是 `no`**。改用与发行版默认不同的三项（`MaxAuthTries` / `ClientAliveInterval` /
`ClientAliveCountMax`）做对照，三项都回到默认值，才是有效证据。
结论：**「实测值 == 托管值」不能证明写入生效**，验证必须用有区分度的样本。

**T3 · 探针两个小坑**：
① `Runtime.evaluate` 报 `-32601 'Runtime.evaluate' wasn't found`，真因是某处 `evalJs`
**漏传 `sessionId`**，命令打到了浏览器级目标（Browser 域没有 Runtime）；
② 断言「侧边菜单出现 X」用 `document.body.innerText` 失败，但页面其实是对的——
刚登录落在 `#/overview/workspace` 时「运维工具」处于**折叠态**，`innerText` 只含可见文本，
`aside.textContent` 才能取到。
修法：`evalJs` 对会话做兜底并带方法名诊断（另加重试以覆盖 SPA 跳转瞬间的上下文切换）；
菜单断言改用 `textContent` 并补一条数据级取证（`/menu/all` 响应体含该路由节点）。

---

## 六、关键提交

| 构建 | 提交 | 内容 |
| --- | --- | --- |
| #95 | `ee01345` | 后端 S0.2+S1：MongoDB 三集合 + 类别/项种子 + 一键生效 9 道闸门 + 历史与按历史恢复 |
| #96 | `c84ee9b` | 停止托管：宿主 `sys.unmanage` + 服务/接口链路（纯净卸载） |
| #97 | `73af149` | 前端 S2：四类别页签 + 配置项增删改 + 预演（9 道闸门可视）+ 停止托管 + 变更历史详情与恢复 |
| #98 | `6bfb8ec` | 前端修复：变更历史默认展示全部类别 |

宿主代理 `hostagent.py` 与文档（`api-spec` / `production-deployment` / `AGENTS` / `lessons`）
随本次一并联调落库；宿主代理经 `rebuild_hostagent.py` 幂等重建后重启，op 总数 46。

---

## 七、遗留与建议

| # | 项 | 说明 / 建议 |
| --- | --- | --- |
| 1 | chrony 分支未在本机实测 | 本机是 systemd-timesyncd；provider 自适应逻辑已实现（候选表按 `detect` 文件存在性判定），建议在一台用 chrony 的机器上补一次生效/卸载验证 |
| 2 | sysctl 内核模块类配置项 | `tcp_congestion_control=bbr` / `default_qdisc=fq` 默认**不托管**（本机未加载 `tcp_bbr`）。dry-run 能拦下不存在的键，但「模块未加载」属前置条件，建议后续在预演里增加「模块可用性」提示 |
| 3 | 并发互斥未做压测 | `6026` 只有代码层与单线程验证；如需高强度并发场景，建议补多客户端并发用例 |
| 4 | limits 只对新会话生效 | 页面与文档均已显式提示；**已在运行的 systemd 服务**需在其 unit 里设 `LimitNOFILE`，本模块不会改 unit |
| 5 | 停止托管不支持跨类别批量 | 设计如此（一次只动一个片段，降低误删面），需逐类操作 |
| 6 | sshd 改端口需人工同步 | 改 `Port` 需同步防火墙/NAT，页面已有提示；`Port` 默认不托管以避免自锁 |
| 7 | 停止托管会删除空的 drop-in 目录 | 如本机 `sshd_config.d` 会被一并删掉（主配置里的 `Include` 保持不变，行为不受影响）；若希望保留空目录，可去掉 `sys.unmanage` 里的 `os.rmdir` |

### 运维要点

- 依赖宿主执行通道（`documents/production-deployment.md` 第十一节），`sys.*` 需要协议版本 ≥ 1；
- 配置回退两条路：「按历史一键恢复」（回到某次变更之前/之后）与「停止托管」（回到发行版原状）；
- 容器回退仍用 `HOST_DEPLOY/.env` 的 `IMAGE_TAG` + `docker compose up -d`；
- 排查现场：`/etc/*/99-serverpanel.conf` 与同目录 `.psm.bak.*`；变更详情页保留前后全文与宿主阶段明细。
