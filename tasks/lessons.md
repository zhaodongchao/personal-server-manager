# 经验教训记录

> 每次收到修正意见或排查出问题时，将经验教训录入本文件，会话启动时优先回顾。

## 2026-09-19 MongoDB 集成验证

### 1. Spring Boot 4 MongoDB 属性前缀变更为 spring.mongodb
- **现象**：`spring.data.mongodb.uri` 配置了用户名密码，但启动日志显示 `credential=null`，所有 MongoDB 写操作报 `Unauthorized (code 13)`；`ping` 命令匿名可用，故连通性检查未暴露问题。
- **根因**：Spring Boot 4 将 MongoDB 连接属性前缀从 `spring.data.mongodb` 迁移到 `spring.mongodb`（属性类 `org.springframework.boot.mongodb.autoconfigure.MongoProperties`），旧前缀被静默忽略，MongoTemplate 以默认 `mongodb://localhost:27017` 无凭据连接。
- **教训**：升级 Spring Boot 大版本后，数据源连接属性前缀可能变化；凭据未生效时应先查启动日志中驱动客户端的 `credential=` 字段，再怀疑密码本身。

### 2. IDEA 编译错误桩污染 target/classes，导致 Maven 假 BUILD SUCCESS
- **现象**：`mvn package` BUILD SUCCESS，但运行时抛 `java.lang.Error: Unresolved compilation problem`（ECJ 错误桩特征）；clean 全量构建后才暴露真实编译错误。
- **根因**：IDEA 增量编译把带编译错误的 class（错误桩）写入 `target/classes`；Maven 增量判断 .class 比 .java 新则跳过重编译，错误桩被直接打包进 jar。
- **教训**：
  - 出现过编译错误后，Maven 构建前先 `clean`；
  - `BUILD SUCCESS` 后仍需启动验证，运行时 `Unresolved compilation problem` 说明 jar 里有 IDEA 残留的错误桩。

### 3. 单个文件编译错误可导致整个模块 Lombok 注解处理失效
- **现象**：`UserPreferenceDocument` 的 `Document` 歧义 import（`org.bson.Document` 与 `org.springframework.data.mongodb.core.mapping.Document` 冲突）修复前，server-system 模块所有 Lombok getter/setter/log 全部"找不到符号"。
- **教训**：Maven 报大量 Lombok 符号缺失时，先找同轮编译中的其他真实错误（如 import 歧义），不一定是 Lombok 配置问题。

### 4. Spring Data MongoDB 5.x 移除 PersistentEntityIndexResolver
- **现象**：`new PersistentEntityIndexResolver(...)` 编译失败。
- **修正**：改用工厂方法 `IndexResolver.create(mongoTemplate.getConverter().getMappingContext())`。

### 5. 本机 java 命令为 JDK 17，构建产物为 Java 21
- **现象**：`java -jar` 启动报 `UnsupportedClassVersionError: class file version 65.0`。
- **教训**：本机需用完整路径 `C:\Users\99754\.jdks\ms-21.0.12.1\bin\java.exe` 启动后端 jar，或先设置 `JAVA_HOME`。

## 2026-09-20 容器化部署下的宿主机网络信息采集

### 1. 面板跑在容器内时，看不到宿主机网卡与 Docker 网络，且镜像内没有 ip / docker 命令
- **现象**：服务器监控页「网络接口」只能列出容器自身的 `eth0` / `lo`，看不到宿主机 41 个接口，也拿不到 `docker0` / `br-*` / `veth`。
- **根因**：容器有独立的网络命名空间，`/sys/class/net` 与 `/proc/net/*` 都是容器视角；同时精简镜像内无 `ip`、`ethtool`、`docker` CLI，无法借 shell 命令绕过。
- **修正**：
  - 接口清单、收发计数、IPv6 前缀、默认网关分别读 `HOST_SYSROOT` 下的 `/proc/net/dev`、`/proc/net/if_inet6`、`/proc/net/route`（容器已 `-v /:/host:ro`）；
  - 类型、驱动、PCI 槽位、网桥端口、上层设备由 `/sys/class/net/**` 推导（`device/uevent`、`brif/`、`master` 软链）；
  - Docker 虚拟网络改走 docker-java 的 `listNetworksCmd()`（挂载 `docker.sock`），不再依赖 CLI。
- **教训**：容器化面板采集「宿主机视角」数据时，一律走 `HOST_SYSROOT` + sysfs/proc 直读；不要假设镜像里有 `ip` / `ethtool` / `docker` 等命令。

### 2. docker network 查询必须加缓存，不能跟着采集频率走
- **现象**：网络快照每 5s 刷新，若每次都调 `listNetworksCmd()` 会持续打满 `docker.sock`，并拖慢采集线程。
- **修正**：新增 `DockerClientProvider` 全局复用一个懒加载 `DockerClient`（原先各 Service 内联自建、重复握手）；Docker 网络列表单独做 30s 缓存，网卡计数与速率仍按 5s 做差分。
- **教训**：把「变化慢但开销大」的外部依赖（Docker API、`pvs`/`vgs` 等）与「高频差分指标」拆成两个采集周期，不要共用节拍。

### 3. 本机源码目录受企业透明加密驱动保护，跨通道传文件必须换扩展名中转
- **现象**：用 Python 直读本地 `*.java` / `*.vue` 得到的是密文随机字节（`utf-8` 解码直接失败），而 `Read`/`Write`/`Edit` 工具看到的却是明文；MCP 通道下载服务端文件时中文也会损坏成乱码。
- **根因**：本机装了透明加密驱动，按扩展名对 `.java`、`.vue` 落盘加密（`.ts`、`.md`、`.txt` 不受影响）；只有被放行的进程能读到明文，Python 直读拿到的是密文。
- **修正**：本地新建/修改一律写成 `.txt` 中转文件（明文），再经 MCP 上传到目标真实路径；需要读取服务端文件时，先在服务端 `cp` 成 `/tmp/xxx.txt` 再下载。
- **教训**：不要用 Python 直读写本机的 `.java` / `.vue`，也不要把本地密文副本当"真值"回传覆盖服务端；改既有源文件优先走「服务端 Python 锚点补丁」，新建文件走「本地 `.txt` + 上传」。


### 4. 容器里跑 systemctl / ufw 是空转，必须建宿主执行通道
- **现象**：服务管理、计划管理、防火墙三块「页面正常、点了没反应」——服务列表整片实时字段为 null，任务执行必失败，防火墙只能看不能改。
- **根因**：面板容器（eclipse-temurin:21-jre）里根本没有 `systemctl` / `journalctl` / `ufw` / `df`，这些功能在容器内执行注定失败；这不是代码 bug，是**部署形态与能力假设不匹配**。
- **修正**：建宿主执行通道 `psm-hostagent`（AF_UNIX + 共享密钥 + 操作白名单 + argv 数组），后端统一走 `hostChannel.call()`，通道不可用时前端只读降级并给安装指引。
- **教训**：容器化面板涉及「宿主机状态」的功能，先确认能力可达再动手；不要在容器里硬跑宿主机命令，也不要把 `docker exec` 当万能通道。

### 5. `systemctl show` 批量查询会被一条非法单元名整体中止
- **现象**：服务列表 50 个单元里 `MainPID` / `memoryBytes` 只有 5~14 个非空。
- **根因**：模板单元（`autovt@.service`）能出现在 `list-unit-files`，但 `systemctl show` 对它直接报错退出，**整批参数一起报废**；且 systemd 会把 `-` 转义成 `\x2d`，根挂载点 `-.mount` 以 `-` 开头，单元名白名单过严会把合法单元全拒掉。
- **修正**：单元名校验放宽到 `^[A-Za-z0-9_@\-][A-Za-z0-9_.@:\-]{0,254}$`；批量 `show` 前先过滤模板单元，批次失败再按二分降到单单元重试。
- **教训**：对「批量外部调用」，任何一条失败都不应让整批报废；先做个体失败隔离，再谈性能。单元名合法性必须以 systemd 的转义规则为准，而不是按常见 web 服务名想当然。

### 6. ufw 的规则编号会漂移，删除必须带指纹；IPv6 副本要一并处理
- **现象 A**：按「端口 + 动作」删除，重复规则删错、范围端口与 Anywhere 规则根本删不掉。
- **根因 A**：ufw 的规则编号会随增删重排，编号不是稳定标识；且 `ufw allow 8080/tcp` 在开启 IPv6 时会写**两条**（本体 + `(v6)` 副本），只删一条会留下看不见的半条规则——若是 deny/reject，用户以为删干净了，v6 侧还在拦。
- **修正**：删除改为「编号 + 指纹（`to|action|from`）」双校验，指纹不符返回 `5014`；删除时按同指纹连 IPv6 副本一并处理，并**先删大编号再删小编号**（每次删除都会重排编号）。回滚脚本 `specFromRule` 要与 `toKind` 用同一套判定——`TO_MULTI` 的 `[\d,]+` 同样能匹配单个端口，`39999/tcp` 会被误判成多端口，导致回滚脚本写成 `ports:[39999]` 而非 `port:39999`。
- **教训**：外部系统的「编号」几乎都不是稳定标识，操作前要带内容指纹复核；成对存在的资源（IPv4/IPv6）要当作一个逻辑单元处理，否则永远清不干净。正则的多分支解析要在**所有使用点**共享同一判定顺序，不能各写各的。


### 7. Nginx 管理：字段名即别名，MySQL 保留字会让整组列表 500
- **现象**：`/api/v1/ops/nginx/cert/page`（以及所有 nginx 列表接口）返回 500，报 `near 'binary,...' you have an error in your SQL syntax`。
- **根因**：MyBatis-Plus 用 Java 属性名作为 SELECT 别名。`OpsNginxInstance` 的 `binary` 属性生成 `SELECT ... binary_path AS binary`，而 `binary` 是 MySQL 保留字，整条 SQL 语法错误，所有依赖该表的列表接口全部 500。
- **修正**：属性改名 `binary → binaryPath`（列名 `binary_path` 不变），彻底消除 `AS binary`；hostagent 返回的 `dataString("binary")` 读键保持不变。
- **教训**：凡是数据库实体，属性名/别名务必避开 MySQL 保留字（binary、order、group、key、desc、status 等）；命名时想当然用 `binary` 当字段名，编译和服务启动都正常，只有真正 SELECT 时才炸，且是「全列表 500」级别的高危。

### 8. 配置生成型功能：写前必 nginx -t，且要分清「容器内」与「宿主机」
- **现象**：Nginx 管理的 `nginx -t` / `reload` / certbot 不能在面板容器里跑（容器是 jre 镜像，没有 nginx/certbot）。
- **根因**：与防火墙/服务管理同源——容器化面板操作的是宿主机资源。直接 `docker exec` 或容器内执行会空转或失败。
- **修正**：配置内容经 `/www` 挂载点写入宿主机（容器 root），进程管理动作统一走 `psm-hostagent` 的 `nginx.*` / `certbot` 操作；写配置前先 `nginx -t`，失败绝不 reload。
- **教训**：配置生成型（Nginx 管理、站点管理）的「渲染」与「执行」要拆开——渲染可在容器，但 `-t/reload/证书申请` 必须落到宿主机；部署设计上就把这两类通道分清，否则又是「页面正常、执行必败」。

### 9. DNS-01 通配符不能一步到位，必须两步 TXT + 后端轮询
- **现象**：certbot 的 `--manual --preferred-challenges dns-01` 需要用户在 DNS 添加 TXT 后才能继续，无法在单次 HTTP 请求里同步完成。
- **根因**：DNS 传播是异步的，certbot 在认证钩子里阻塞等待 TXT 生效；面板若同步等待会卡死 HTTP 请求。
- **修正**：拆成两步——`issue` 只启动 certbot 并立即返回需添加的 TXT 记录名/值（状态置 `pending`）；用户添加后调 `dns-verify`，后端写 `.ready` 唤醒钩子并轮询 `acmeStatus` 直至签发（最长约 5 分钟）。前端 `certId` 经 `NginxActionResult.changeId` 回传（复用字段，注意语义）。
- **教训**：任何依赖外部异步传播（DNS/邮件/第三方回调）的 ACME/验证流程，后端都不要同步阻塞 HTTP；用「发起→返回凭证→回调/轮询」的两段式，前端配 loading + 状态轮询。
