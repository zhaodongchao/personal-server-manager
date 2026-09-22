# 定时任务管理 / 定时任务日志 验收报告

> 模块：应用栈 → 定时任务管理（菜单 504）/ 定时任务日志（菜单 505）
> 参考实现：xxl-job（借形不借体：取「调度中心 / 执行器」分层与「调度日志双段」模型，
> 不做注册中心与心跳，不做在线编码 GLUE）
> 验收时间：2026-09-22　验收范围：S0 设计 → S1 后端 → S2 前端 → S3 安全与端到端

---

## 一、交付与发版记录

| 阶段 | 提交 | Jenkins | 镜像 tag | 结果 |
| ---- | ---- | ------- | -------- | ---- |
| S1 后端（三表 + 4 类 handler + 27 接口） | `36f8aa6` | #101 | `master-b101-36f8aa6` | SUCCESS |
| S2 前端（双页面 + 动态表单 + 执行器管理） | `c8874d9` | #102 | `master-b102-c8874d9` | SUCCESS |
| S3 修复（闸门泄漏 / 终止被覆盖 / 丢弃语义） | `bd33f41` | #103 | `master-b103-bd33f41` | SUCCESS |

部署形态：`psm-backend`(8080) 与 `psm-frontend`(3000，nginx 反代 `/api`、`/ws`)，
`network_mode: host`，两容器均 `healthy`。数据库迁移 Flyway **V11** 已应用。

---

## 二、交付物清单

**数据库（Flyway V11）**

- `app_executor` / `app_job` / `app_job_log` 三表
- seed 内置执行器 `serverpanel-builtin`（不可删除、不可停用）
- 菜单 504 / 505（`C` 型）与 8 个按钮权限点（`F` 型）：5041 新增/编辑、5042 删除、
  5043 启用/停用、5044 立即执行、5045 停止执行、5046 执行器管理、5047 危险任务确认、5051 日志清理
- 菜单与按钮均授权给 role 1（共 10 条 `sys_role_menu`）

**后端**

- `server-common`：`ProtectedUnits`（由 `ops.constant` 上提）、`InternalTask` SPI、错误码 6030–6040
- `server-appstack`：`JobScheduler`（调度池，poolSize 4）/ `JobDispatcher`（执行池，虚拟线程）/
  `JobLogRecorder` / `JobChannel` / `JobHttpClient` / `JobCron` / `JobSupport` / `JobEnums` /
  `JobProperties`，4 类 handler（SHELL / HTTP / SERVICE / INTERNAL），27 个接口
- `server-file` `RecycleCleanupTask`、`server-ops` `NginxCertRenewTask`、
  `server-appstack` `DbBackupTask` / `JobLogPurgeTask`（内置任务，各自模块以 `@Component` 贡献）
- `application.yml` 新增 `serverpanel.job.*`（`scheduler.enabled=false` 可一键停掉全部定时任务，
  是不需要回滚版本即可止损的故障处置手段）

**前端**

- `api/job.ts`（21 端点）、`api/job-log.ts`（6 端点）
- `views/appstack/job/{index.vue, components/JobDrawer.vue, components/ExecutorDrawer.vue}`
- `views/appstack/job-log/{index.vue, components/LogDetailDrawer.vue}`

**关键设计约束（已守住）**

- **宿主侧零新增 op**：SHELL 复用 `host.exec`，SERVICE 复用 `service.action`
- **零新增三方依赖**：`CronExpression` / `ThreadPoolTaskScheduler` / `HttpClient` 均在现有 Spring 7 栈内
  （未引入 Quartz，未恢复 cron-utils）
- **调度池与执行池分离**：一个 300 秒的备份任务不会阻塞面板全部定时任务

---

## 三、接口清单（27 个）

### 定时任务 `/api/v1/appstack/job`（21）

| 方法 | 路径 | 权限 | 审计 |
| ---- | ---- | ---- | ---- |
| GET | `/page` | `appstack:job:list` | — |
| GET | `/stats` | `appstack:job:list` | — |
| GET | `/handlers` | `appstack:job:list` | — |
| GET | `/commands` | `appstack:job:list` | — |
| GET | `/options` | `appstack:job:list` | — |
| POST | `/cron/validate` | `appstack:job:list` | — |
| GET | `/{id}` | `appstack:job:list` | — |
| GET | `/{id}/next-times` | `appstack:job:list` | — |
| POST | `/` | `appstack:job:save` | ✔ 高危 |
| PUT | `/{id}` | `appstack:job:save` | ✔ 高危 |
| DELETE | `/{id}` | `appstack:job:delete` | ✔ 高危 |
| POST | `/{id}/copy` | `appstack:job:save` | ✔ 高危 |
| POST | `/{id}/enable` | `appstack:job:toggle` | ✔ 高危 |
| POST | `/{id}/disable` | `appstack:job:toggle` | ✔ 高危 |
| POST | `/{id}/run` | `appstack:job:run` | ✔ 高危 |
| POST | `/{id}/stop` | `appstack:job:stop` | ✔ 高危 |
| GET | `/executor/list` | `appstack:job:list` | — |
| POST | `/executor` | `appstack:job:executor` | ✔ 高危 |
| PUT | `/executor/{id}` | `appstack:job:executor` | ✔ 高危 |
| DELETE | `/executor/{id}` | `appstack:job:executor` | ✔ 高危 |
| POST | `/executor/{id}/test` | `appstack:job:executor` | — |

### 定时任务日志 `/api/v1/appstack/job-log`（6）

| 方法 | 路径 | 权限 | 审计 |
| ---- | ---- | ---- | ---- |
| GET | `/page` | `appstack:joblog:list` | — |
| GET | `/statistics` | `appstack:joblog:list` | — |
| GET | `/retention` | `appstack:joblog:list` | — |
| GET | `/{id}` | `appstack:joblog:list` | — |
| GET | `/{id}/output` | `appstack:joblog:list` | — |
| DELETE | `/clear` | `appstack:joblog:clear` | ✔ 高危 |

> 审计口径：共 13 处 `@Audit`。读接口与 `/executor/{id}/test` 刻意不标注 ——
> 「探测连通性」不是一次变更，不该混进变更审计。

---

## 四、错误码与实测结论（6030–6040）

| 码 | 含义 | 实测触发方式 | 结果 |
| -- | ---- | ------------ | ---- |
| 6030 | 定时任务不存在 | 对已删任务操作 | 通 |
| 6031 | cron 非法或间隔过短 | `abc` / `*/2 * * * * ?`（最小 10 秒） | 通 |
| 6032 | 任务执行中或执行器被引用 | 删仍被任务引用的执行器 | 通 |
| 6033 | 处理器参数不合法 | 路由/阻塞策略非法、timeout 超上限、retry 超上限、SHELL 缺 command、SERVICE 动作不在宿主动作集、HTTP 方法非法、期望码越界、SSRF（`169.254.169.254`、`file:` 协议） | 通 |
| 6034 | 执行器不存在或不可用 | 引用不存在的 executorId | 通 |
| 6035 | 内置执行器受保护 | `DELETE /executor/1` | 通 |
| 6036 | 命令不在白名单 | SHELL `curl` / `bash -c` | 通 |
| 6037 | 日志不存在 | 查不存在的日志 | 通 |
| 6038 | 任务名 / AppName 已存在 | 重名建任务 | 通 |
| 6039 | 需确认关键字 | 删任务 / 停任务 / 删执行器 / 清日志 确认字缺失或错误、受保护单元破坏性动作未确认 | 通 |
| 6040 | 任务未被执行（被丢弃或前置检查拦下） | DISCARD_LATER 二次触发 | 通（S3 新增） |

S1 拒绝路径 **17/17 全中**；S3 扩充后危险动作与拒绝断言合计 **40/40**。

---

## 五、权限按角色真实拦截（S3）

**方法**：不直插数据库，而是走产品自身的系统管理接口创建第二个非管理员账号 ——
角色 `job_viewer`（仅挂 500 / 504 / 505 三个菜单，即只有两个 `*:list` 权限码）+ 用户 `jobviewer`。

**结果：29 项断言全通过。**

| 观测点 | 结果 |
| ------ | ---- |
| admin 权限码 71 个，job 相关 10 个 | — |
| jobviewer 权限码 2 个：`appstack:job:list`、`appstack:joblog:list` | 无任何写码 |
| 读接口 7 个（page / handlers / options / stats / job-log page / statistics / retention） | 全部放行 |
| 写接口 12 个（save / update / delete / copy / enable / disable / run / stop / executor 增删测 / clear） | **全部 403** |
| 给角色补 `job:run` + `joblog:clear` 后 | run 放行；未补的 save / delete **仍 403** |
| 动态路由树（`GET /menu/all`） | 含 `/appstack/job`，不含执行器管理入口 |

补授权后「同一批接口一部分放行、一部分仍被拒」是这次验收最关键的一条证据：
说明拦截是按**权限码**判定，而不是按账号或角色硬编码。

---

## 六、页面验收（S2，CDP 远程真实浏览器）

**37 PASS / 0 FAIL**，其中三条是设计核心主张的实证：

1. **动态表单真的随 handler 变形** —— INTERNAL（内置任务下拉 + 参数）→ SHELL（命令下拉只列白名单、
   参数按「每行一个」录入）→ SERVICE（服务单元 + 动作），前端不含任何 handler 分支代码；
2. **服务保护清单联动生效** —— unit=`nginx.service` + action=`stop` 时，「确认关键字」输入框
   自动出现并预填 `APPLY nginx.service`，用户在保存前就知道将来执行会被要求确认；
3. **cron 保存前校验** —— 合法性 + 相邻间隔下限 + 未来 5 次触发时间，非法表达式在保存前拦下。

> 说明：无头容器缺中文字体，截图里中文是方框（tofu），属探针环境问题，DOM 断言与文字匹配不受影响。

---

## 七、三类危险动作完整闭环（S3）

**40 项断言全通过。** 造长任务的手段值得记一笔：白名单里没有慢命令，但 `journalctl -f`（跟随模式）
是无限阻塞的，配合任务 `timeoutSec` 就得到一个可控的长任务，全程不需要往任务里塞任何凭据。

### COVER_EARLY

- 第 1 次运行 → `RUNNING`；第 2 次触发被接受且放行
- 前次日志转 `KILLED`，原因含 `COVER_EARLY`；本轮为 `RUNNING`；**任一时刻只有一个 `RUNNING`**
- 日志总数恰为 2，不出现「双跑」

### 停止执行（STOP）

- 缺确认字 / 错确认字 → `6039`
- 正确确认字 → `stopped=true`，返回的 `logId` 与运行中那条日志一致
- 日志转 `KILLED`，原因明说「面板侧执行线程已请求终止；SHELL/SERVICE 的真实执行体在宿主侧，进程可能仍在运行」
- 重复停止 → `stopped=false`「该任务当前没有正在执行的实例」
- 停用后任务 `status=0`

### 清理日志（CLEAR LOG）

- 缺确认字 / 错确认字 → `6039`；只给确认字不给任何条件 → `6033`「为避免误删全表，至少要指定一个条件」；
  非法 status → `6033`
- 条件 + 正确确认字 → 删除条数与命中行数**精确相等**，全库 `total` 恰好减少同样条数
- 未命中条件的日志未被误删；**清理日志不影响任务本身**

### 阻塞策略对照

| 策略 | 二次触发行为 | 实测 |
| ---- | ------------ | ---- |
| SERIAL | 等待前次结束后执行 | 连续两次均 `SUCCESS` |
| DISCARD_LATER | 直接丢弃，记 `DISCARDED` + `trigger_code=500` | 通过 |
| COVER_EARLY | 覆盖前次（标 `KILLED`）后执行本轮 | 通过 |

---

## 八、审计日志核对（S3）

**结论：落库完整，且反向对照成立。**

- 敏感动作 13 个端点全部落 `sys_audit_log`，字段含 `operator` / `module` / `action` /
  `request_method` / `request_uri` / `result_code` / `duration_ms` / `ip` / `user_agent` / `error_msg`
- 失败动作确有记录，且 `error_msg` 保留真实原因（例：「内置执行器不允许删除」「该操作不可逆，请在确认框中输入：DELETE JOB xxx」）
- **反向对照**：未标注 `@Audit` 的 `POST /executor/{id}/test` 在审计表中条数为 **0** ——
  说明切面是精确挂在注解上的，不是「整个 controller 无差别记录」
- 非管理员账号的操作单独以 `operator=jobviewer` 留痕
- 确认关键字校验通过后，`job:save` / `job:delete` 这类高危写操作均能看到成对的
  「失败（缺确认字）→ 成功（补确认字）」记录

---

## 九、S3 发现并修复的三个缺陷

这三个都是**编译通过、单次冒烟通过、接口全绿**却照样存在 —— 只有端到端连续操作才暴露。

### 缺陷 1（P0）：任务实际只能跑一次

- **现象**：同一任务第二次触发起一律被阻塞策略丢弃。日志证据：`s3-perm-probe` 第二次执行报
  「前次执行未结束，等待 60 秒仍未取得执行权」；`s3-serial` 第二次恰好等满 `timeoutSec`（30 秒）才被丢弃。
  即定时任务只会在第一次触发时真正执行 —— **cron 任务等于跑一次就废**。
- **根因**：任务级闸门用 `ReentrantLock`，闸门在**调用线程**（HTTP / 调度线程）上 `tryLock`，
  却在**执行线程**（`execPool` 虚拟线程）的 `finally` 里 `unlock`。`ReentrantLock.unlock()`
  校验持有者线程，跨线程释放抛 `IllegalMonitorStateException`；该异常发生在 `finally` 中，
  被 `Future` 静默吞掉 —— 闸门永不释放。
- **修法**：`ReentrantLock` → `Semaphore`（acquire / release 不绑定线程），三种阻塞策略语义完全等价。

### 缺陷 2（P1）：点了「停止」，日志却显示「失败」

- **现象**：stop 后日志状态为 `FAILED` 而非 `KILLED`，`handle_msg` 是
  「执行流程异常：### Error updating database」。
- **根因**：`stop()` / `coverEarly()` 先 `future.cancel(true)` 再 `markKilled()`，
  被中断的执行线程抢先把 `handle_*` 回填成 `FAILED`，覆盖了刚写入的终态。
- **修法**：① 先落终态再中断；② `recordHandle` / `markKilled` 都以 `status = RUNNING` 为前置条件，
  形成**单向门**；③ `recordHandle` 返回更新行数，为 0 说明日志已被终止标记，
  此时不再回写任务的 `lastStatus` / `failStreak`（否则一次被终止的执行会把任务标成失败并累积熔断计数）。

### 缺陷 3（语义缺口）：接口对「没跑」报成功

- **现象**：被丢弃时 `/run` 仍返回 `code=0` +「已派发执行，请到日志查看结果」，实际什么都没跑，
  用户必须自己进日志页才发现。
- **修法**：`run()` 读回该日志，若为 `DISCARDED` 则报新错误码 **6040** 并带上真实原因。
  前端 run 已有 `try/catch`（错误提示由请求拦截器统一给出），**无需前端改动**。

---

## 十、已知限制与未修复项（已确认边界，非遗漏）

| # | 项 | 说明与建议 |
| - | -- | ---------- |
| 1 | **审计 `result_code` 对业务失败统一记 500** | 业务码（6035 / 6038 / 6039）只出现在 `error_msg` 文本里。**建议后续新增独立列 `biz_code`，不要重载 `result_code`** —— 重载会让新旧行语义混用，比维持现状更糟 |
| 2 | **被拒请求（403）不留审计痕** | `@SaCheckPermission` 先于 `@Audit` 生效，因此「越权尝试」不可见。若需要该视角，应在鉴权层单独记录，不宜把 `@Audit` 前移（前移会把「未执行」记成「已执行」） |
| 3 | **COVER_EARLY / 停止对 SHELL / SERVICE 只能逻辑标 KILLED** | 真实执行体是宿主机 `host.exec` 的子进程，宿主代理没有「按 id 终止某次 host.exec」的 op，进程会跑到自己的超时。这是「宿主零改动」的代价，**UI 已明示**；若要真杀，需新增宿主 op，打破本模块最大的收益 |
| 4 | **`@Audit(risky = true)` 只做标记，未触发二级认证** | 注解 Javadoc 写「高危将同时要求二级认证」，实现里没有对应拦截器 —— **文档与实现不一致**，建议要么补拦截器要么改注释 |
| 5 | **外部执行器无注册中心 / 心跳** | 只有连续失败 `failStreak >= 3` 的熔断标记 + 手动连通性探测。v1 的定位是「单面板」，不做分布式基础设施 |
| 6 | **停用的任务仍可手动执行** | **设计如此**（代码注释：「先试一下再启用」）。cron 触发仍会被 `preCheck` 拦下并发 `DISCARDED` —— 验收已分别验证两条路径（C 与 C2） |
| 7 | **`locks` / `running` 两个内存 map 不随任务删除清理** | 随任务数线性增长，量级可忽略；进程重启即清空（符合单实例定位） |
| 8 | **`app_job_log` 无「无条件清空」入口** | 刻意为之：不给「一键清空全表」的按钮，必须至少给一个条件 |
| 9 | 历史遗留（非本模块） | `views/ops/server-config/**` 有 8 处 `vue-tsc` 类型错误，建议单独清理 |

---

## 十一、复现方式

验收脚本随仓库交付在同级目录（`psm/up/`，仅本地留存，不随仓库发布）：

| 脚本 | 覆盖 |
| ---- | ---- |
| `s3_security_check.py` | 第五节：建角色/用户 + 29 项权限断言（`--keep` 保留账号供人工复核） |
| `s3_audit_probe.py` | 第八节：跑一串成功/失败动作 + 反向对照，供审计表核对 |
| `s3_danger_check.py` | 第七节：COVER_EARLY / SERIAL / DISCARD_LATER / STOP / CLEAR LOG 共 40 项断言 |
| `patch_job_s3.py` | 第九节的三个缺陷修复补丁（锚点唯一性校验，`--apply` 才落盘） |

编译预检（**必须带 `-u 1000:1000`**，否则 root 属主 `target/**` 会让 Jenkins 无法 `clean`）：

```
docker run --rm -u 1000:1000 \
  -v /data/jenkins/m2:/m2 -v /data/jenkins/maven-conf:/m2conf \
  -v <repo>:/repo -w /repo/backend --network host \
  maven:3.9-eclipse-temurin-21 \
  mvn -s /m2conf/settings-docker.xml -Dmaven.repo.local=/m2 -DskipTests -pl server-boot -am compile
```

---

## 十二、结论

S1–S3 全部验收项通过：**权限 29/29**、**页面 37/37**、**危险动作 40/40**、**拒绝码 17/17 + 错误码 6030–6040 全覆盖**、
**审计落库完整且反向对照成立**。

S3 的价值集中在第九节：它抓出了三个「接口全绿但实际不可用」的缺陷，其中 P0 会让模块在真实使用中
（第二次触发、cron 重复调度）直接失效。这三处已修复并随 `bd33f41` / Jenkins #103 上线，
随后以同一套脚本复跑确认全绿。第十节的 9 项均为**已确认的边界或待确认项**，不是遗漏。

遗留待办：第十节第 1、2、4 项需要产品/架构决策（是否加 `biz_code` 列、是否需要记录越权尝试、
`risky` 与二级认证的口径），建议单独立项。
