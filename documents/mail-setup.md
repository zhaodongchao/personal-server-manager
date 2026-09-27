# 邮件与自助注册配置说明

> 配套文档：[api-spec.md 第 11 节](./api-spec.md)。本文聚焦「怎么把邮件验证码登录与自助注册跑通」，
> 与 [oauth-setup.md](./oauth-setup.md)（第三方登录）同一哲学：**按配置启用**——
> 没配 SMTP 时前端入口自动隐藏，绝不出现「点了必然报错」的误导按钮。

ServerPanel 的邮箱能力只服务两件事实名场景：

- **邮箱验证码登录**（`purpose=login`）：账号已存在，邮箱已登记，收码后登录。
- **自助注册**（`purpose=register`）：开放后任何人可经邮箱验证码自助建号，新账号**不分配角色**。

---

## 1. 配置总览（全部环境变量注入）

环境变量名由 `application.yml` 的 `${XXX:默认值}` **占位符名**决定（不是配置键推导），
因此首账号仍是 `MAIL_HOST` 而非 `SERVERPANEL_MAIL_ACCOUNTS_0_HOST`。

| 环境变量 | 落到配置键 | 默认 | 说明 |
| ---- | ---- | ---- | ---- |
| `MAIL_HOST` | `serverpanel.mail.accounts[0].host` | `smtp.qq.com` | SMTP 地址 |
| `MAIL_PORT` | `...accounts[0].port` | `465` | 465=SSL 直连；587=STARTTLS（`MAIL_SSL=false`） |
| `MAIL_USERNAME` | `...accounts[0].username` | — | SMTP 账号（多为完整邮箱） |
| `MAIL_PASSWORD` | `...accounts[0].password` | — | **授权码**，非邮箱登录密码 |
| `MAIL_SSL` | `...accounts[0].ssl` | `true` | 587 时设 `false`（自动启用 STARTTLS） |
| `PANEL_MAIL_FROM` | `...accounts[0].from` | — | 发件人，多数服务商要求 = `MAIL_USERNAME` |
| `PANEL_MAIL_PURPOSES` | `...accounts[0].purposes` | `all` | 该账号负责目的：`register`/`login`/`all`，逗号分隔 |
| `PANEL_REGISTER_ENABLED` | `serverpanel.register.enabled` | `false` | 自助注册总开关 |
| `PANEL_REGISTER_EMAIL_DOMAINS` | `serverpanel.register.allowed-email-domains` | 空 | 允许注册的邮箱域名白名单，逗号分隔，空=不限制 |

> 授权码 ≠ 邮箱登录密码。QQ/163 等在邮箱设置的「POP3/IMAP/SMTP 服务」里单独生成
> （QQ 为 16 位）。`PANEL_MAIL_FROM` 与 `MAIL_USERNAME` 不一致会被多数服务商拒发（553/501）。

---

## 2. 多 SMTP 发件账号

`spring.mail` 只支持单数据源，多账号需手写发件客户端池——本项目的 `MailSenderService`
已改造为**按 purpose 路由的多账号池**：配置 `serverpanel.mail.accounts` 列表，
每个账号独立 host/port/账号/密码/from/ssl，并声明 `purposes`。

- 选取规则：发码时按 `purpose` 选**第一个** `purposes` 含该目的或 `all` 的账号；
  无任何匹配时回退列表首个账号，保证可发出。
- 可用性：`accounts` 中至少一个 `host`+`username` 齐全即视为功能启用；
  `/auth/mail/enabled` 的 `enabled` 字段返回 `true`。

**追加第 2、第 3 个账号**（索引环境变量，Spring 宽松绑定）：

```bash
SERVERPANEL_MAIL_ACCOUNTS_1_HOST=smtp.163.com
SERVERPANEL_MAIL_ACCOUNTS_1_PORT=465
SERVERPANEL_MAIL_ACCOUNTS_1_USERNAME=admin@163.com
SERVERPANEL_MAIL_ACCOUNTS_1_PASSWORD=授权码
SERVERPANEL_MAIL_ACCOUNTS_1_FROM=admin@163.com
SERVERPANEL_MAIL_ACCOUNTS_1_SSL=true
SERVERPANEL_MAIL_ACCOUNTS_1_PURPOSES=register      # 仅用于注册发码
```

典型用法：把 `purpose=register` 的验证码走企业邮账号、`purpose=login` 走另一个，
互不干扰且便于区分来源。

---

## 3. systemd 注入（推荐 override，别改主 service 文件）

`scripts/install.sh` 用 heredoc 全量覆盖 service 单元，手写的 `Environment=` 一重跑就没了。
因此用 drop-in：

```bash
sudo systemctl edit serverpanel
```

```ini
[Service]
Environment="MAIL_HOST=smtp.qq.com"
Environment="MAIL_PORT=465"
Environment="MAIL_SSL=true"
Environment="MAIL_USERNAME=123456@qq.com"
Environment="MAIL_PASSWORD=abcdefghijklmnop"
Environment="PANEL_MAIL_FROM=123456@qq.com"
Environment="PANEL_REGISTER_ENABLED=true"
# 可选：仅允许公司域名注册
Environment="PANEL_REGISTER_EMAIL_DOMAINS=yourcompany.com,sub.yourcompany.com"
```

```bash
sudo systemctl daemon-reload && sudo systemctl restart serverpanel
```

> systemd 注意：值里含 `%` 写成 `%%`；数值/布尔项建议连默认值一起写，别留空（空串会让
> Spring 类型转换失败）。`install.sh` 已对 `MAIL_PORT`/`MAIL_SSL`/`PANEL_REGISTER_ENABLED`
> 在 shell 侧占位，避免注入空串。

Docker Compose 写在 `serverpanel` 服务的 `environment:` 段；本地开发填 IDEA Run Configuration
的 Environment variables。

---

## 4. 三步验证

```bash
# 1. 两个开关 + 域名白名单（allowedEmailDomains 空数组=不限制）
curl -s http://localhost:8080/api/v1/auth/mail/enabled
# {"code":200,"data":{"enabled":true,"registerEnabled":true,"allowedEmailDomains":[]}}

# 2. 出网连通性（465 最常见；云厂商偶封 587）
timeout 5 bash -c 'cat < /dev/null > /dev/tcp/smtp.qq.com/465' && echo OK

# 3. 冷启动验证：用 register 目的，不要求邮箱已绑定面板账号
curl -X POST http://localhost:8080/api/v1/auth/mail/code \
  -H 'Content-Type: application/json' \
  -d '{"email":"123456@qq.com","purpose":"register"}'
```

`purpose=login` 要求邮箱已绑定账号，首次冒烟请先用 `register`。

---

## 5. 排障对照

| 现象 | 原因与处理 |
| ---- | ---- |
| `enabled:false` | `MAIL_HOST`/`PANEL_MAIL_FROM` 未进进程环境：`systemctl show serverpanel -p Environment` 核实 |
| 发码 500 / 超时 | 端口或出网问题；465 优先，确认防火墙放通出方向 |
| `535 authentication failed` | 用了邮箱登录密码而非**授权码** |
| `553 / 501 Mail from must equal authorized user` | `PANEL_MAIL_FROM` ≠ `MAIL_USERNAME` |
| 注册按钮不显示 | 需 `enabled` 与 `registerEnabled` **同时** true |
| 发码返回 `1036` | 命中邮箱域名白名单限制（见第 1 节 `PANEL_REGISTER_EMAIL_DOMAINS`） |
| 新注册用户登录后 404 | 不应再发生：无角色用户已被路由守卫重定向至 `/pending-activation` 待激活提示页 |

---

## 6. 新注册用户「待激活」提示页

自助注册的新账号**不分配任何角色**，登录后业务菜单为空。此前直接落默认首页会触发 404；
现在前端路由守卫检测到「已登录但 `roles` 为空」时，重定向至 `/pending-activation` 提示页，
明确告知「账号已创建，请联系管理员分配角色，分配后退出重登」。管理员在
「系统管理-用户」分配角色后，用户退出重新登录即可正常进入系统。该页为核心路由，
不受权限拦截影响，未分配角色也能稳定打开。

---

## 7. 发送验证码前的人机校验（点选字符）与发信安全拦截

发送邮箱验证码前必须经过**点选字符人机校验**，并对来源 IP 做**限流与临时封锁**，防止脚本
批量刷邮件 / 枚举账号。

### 7.1 点选人机校验流程

1. 点击「发送验证码」→ 弹出人机校验弹窗，并 `POST /auth/captcha/click` 领取验证码；
2. 弹窗展示一张随机字符图片与提示「请依次点击：X Y Z」，用户按顺序点击图中的目标字符；
3. 前端把每次点击的**相对坐标**（0~1，相对图片宽高）按顺序回传
   `POST /auth/captcha/click/verify`；后端换算成像素后与挑战中记录的目标字符中心逐个比对
   （数量一致 + 顺序一致 + 落在命中半径内），通过即签发一次性发信令牌 `sendToken`（60 秒有效）；
4. 前端自动带上 `sendToken` 调 `/auth/mail/code` 发码。

> **答案只在服务端**：图片随响应下发，但目标字符坐标不下发，脚本即便解析响应也拿不到答案。
> 同一张图最多错 3 次（`PANEL_CAPTCHA_CLICK_MAX_ATTEMPTS`）即作废，前端自动换一张。
> 缺令牌（1037）或令牌失效/已用（1038）都会被拒绝，无法绕过。

> **部署注意**：验证码图片由后端用 JDK AWT 渲染，需要宿主机有可用字体。
> Debian / Ubuntu 精简环境请安装：`apt-get install -y fonts-dejavu-core`。
> 缺字体时接口返回 `1040` 并给出提示，而不是下发空白图。

### 7.2 发信安全拦截（SendGuard，按来源 IP）

`/auth/mail/code` 在「人机校验通过之后、实际发码之前」按来源 IP 做滑动窗口计数：

| 窗口 | 默认上限 | 超出行为 |
| ---- | ---- | ---- |
| 每分钟 | 5（`PANEL_SEND_GUARD_IP_PM`） | 直接拒绝（不封锁，窗口自然回落），返回 `1039` |
| 每小时 | 30（`PANEL_SEND_GUARD_IP_PH`） | 触发**临时封锁**，默认 30 分钟（`PANEL_SEND_GUARD_BLOCK_MIN`），期间该 IP 全部发码请求拒绝（`1039`） |

调整后需 `systemctl edit serverpanel` 写 override 并 `daemon-reload && restart`，或直接改
`application.yml` 的 `serverpanel.captcha.*` 与 `serverpanel.send-guard.*`。

### 7.3 注册用户名实时查重

注册页用户名输入框失焦时调 `GET /auth/register/check-username?username=xxx`，
返回 `{available}` 即时提示「该用户名已被使用」；格式非法直接视为不可用（不查库，
也不作为枚举入口）。最终唯一性仍由 `uk_username` 在注册接口兜底（`1035`）。
