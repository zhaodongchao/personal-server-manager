# ServerPanel 第三方登录接入配置说明

本文档说明如何为 ServerPanel 启用登录页第三方登录（Gitee / GitHub / 钉钉 / 微信 / QQ），涵盖平台应用申请、回调地址填写、环境变量配置、验证步骤与故障排查。

接口契约（请求/响应/错误码）以 `documents/api-spec.md` 第 10 节为准，本文只讲**怎么配、怎么验**。

---

## 一、机制速览

| 项 | 说明 |
| ---- | ---- |
| 实现 | JustAuth 1.16.7（纯 HTTP 客户端，不引入 Spring Security 体系） |
| 账号策略 | **先绑定后登录**：第三方身份必须先由已登录用户在「个人中心 → 第三方账号」主动绑定，之后才能用于登录页一键登录；**不做自动注册**，未绑定的第三方账号一律拒绝（1022） |
| 启用方式 | **按配置启用**：哪个平台配了 client-id，登录页就显示哪个图标；未配置的自动隐藏，无需改代码、无需发版 |
| 回调方案 | 前端回调页：`redirect-uri` 统一指向 `/auth/oauth/callback`，由回调页拿 code/state 换 accessToken；**token 只经 POST 响应返回，不落 URL、不入访问日志** |
| 数据表 | `sys_user_oauth`，由 Flyway 迁移 `V22__user_oauth_binding.sql` 自动建表 |
| 依赖 | Redis 必须可用（state 存 Redis，TTL 默认 300 秒） |

**账号策略为什么这样定**：这是服务器管理面板，不是开放注册的 To C 产品。若放开自动注册，任何持有 Gitee/GitHub 账号的互联网用户都能拿到面板入口。「先绑定后登录」把第三方登录降级为**已存在账号的一种便捷登录方式**，账号的创建权始终掌握在管理员手里。

---

## 二、三步启用

### 步骤 1：申请平台 OAuth 应用

在目标平台创建应用，拿到 `Client ID` 与 `Client Secret`，并登记回调地址（回调地址怎么填见第三节，容易填错）。

### 步骤 2：注入环境变量

凭证**不写入配置文件、不入库**，一律走环境变量（默认值均为空 = 未启用）：

| 平台 | 环境变量 |
| ---- | ---- |
| 回调地址（全局） | `PANEL_OAUTH_REDIRECT_URI` |
| Gitee | `OAUTH_GITEE_CLIENT_ID` / `OAUTH_GITEE_CLIENT_SECRET` |
| GitHub | `OAUTH_GITHUB_CLIENT_ID` / `OAUTH_GITHUB_CLIENT_SECRET` |
| 钉钉 | `OAUTH_DINGTALK_CLIENT_ID` / `OAUTH_DINGTALK_CLIENT_SECRET` |
| 微信 | `OAUTH_WECHAT_CLIENT_ID` / `OAUTH_WECHAT_CLIENT_SECRET` |
| QQ | `OAUTH_QQ_CLIENT_ID` / `OAUTH_QQ_CLIENT_SECRET` |

systemd 部署示例（`/etc/systemd/system/serverpanel.service`）：

```ini
[Service]
Environment=PANEL_OAUTH_REDIRECT_URI=https://panel.example.com/#/auth/oauth/callback
Environment=OAUTH_GITEE_CLIENT_ID=xxxx
Environment=OAUTH_GITEE_CLIENT_SECRET=xxxx
```

Docker 部署在 `docker-compose.yml` 的 `environment:` 段同样注入即可。改完 `systemctl daemon-reload && systemctl restart serverpanel`。

### 步骤 3：重启后端

重启即生效。前端无需改动——登录页会自动拉取已启用平台列表并渲染图标。

---

## 三、回调地址：最容易填错的一步

回调地址必须与**前端路由模式**匹配，否则授权完成后会跳到首页或 404。

本项目前端路由模式由 `VITE_ROUTER_HISTORY` 决定（`frontend/apps/web-antd/src/router/index.ts`）：

| 环境 | 路由模式 | 回调地址应填 |
| ---- | ---- | ---- |
| 生产（`.env.production` 已设 `VITE_ROUTER_HISTORY=hash`） | hash | `https://panel.example.com/#/auth/oauth/callback` ← **必须带 `/#/`** |
| 开发（`.env.development` 未设，默认 history） | history | `http://localhost:5666/auth/oauth/callback`（不带 `#`） |

要点：

- **全平台登记同一个回调地址**，后端 `serverpanel.oauth.redirect-uri` 是全局配置，不按平台区分
- `application.yml` 默认值是 `http://localhost:5666/#/auth/oauth/callback`（带 `#`），**仅适用于把开发前端也切成 hash 模式的情况**；生产务必显式设 `PANEL_OAUTH_REDIRECT_URI`
- 部分平台（微信开放平台）只校验**域名**不校验路径，填域名即可；GitHub/Gitee/QQ 需填**完整路径**
- 生产环境必须用 HTTPS，多数平台不允许 http 回调（localhost 除外）

---

## 四、各平台申请指引

| 平台 | 申请入口 | 资质 | 注意事项 |
| ---- | ---- | ---- | ---- |
| Gitee | https://gitee.com/oauth/applications | 个人即可 | 无需审核，创建后立即可用。回调地址填完整路径 |
| GitHub | https://github.com/settings/developers → OAuth Apps → New OAuth App | 个人即可 | 回调地址填完整路径；Secret 只在创建时可见一次，注意保存 |
| 钉钉 | https://open.dingtalk.com/ → 应用开发 → 创建应用 | 个人/企业均可 | **见下方风险提示** |
| 微信 | https://open.weixin.qq.com/ → 网站应用 | **需企业认证**（300 元/年） | 通过后填域名级回调；个人开发者无法申请，凭证到位后填环境变量即生效 |
| QQ | https://connect.qq.com/ → 应用管理 | **需企业资质审核** | 同微信，凭证到位后零代码生效 |

### 钉钉风险提示（重要）

JustAuth 1.16.7 的 `DINGTALK` 走的是**旧版扫码登录接口**，`2022-06` 之后在钉钉开放平台新建的应用已不支持该接口，联调会直接失败。

若遇到授权失败，需要自建 `DingTalkV2Request` 覆写 `authorize` / `getAccessToken` / `getUserInfo` 三个方法，改用新版端点 `login.dingtalk.com/oauth2/auth`。**该风险不影响其余 4 个平台**，也没必要提前改造——等真正要接钉钉时再处理。

---

## 五、配置项参考

`backend/server-boot/src/main/resources/application.yml`：

```yaml
serverpanel:
  oauth:
    redirect-uri: ${PANEL_OAUTH_REDIRECT_URI:http://localhost:5666/#/auth/oauth/callback}
    state-ttl-seconds: 300
    providers:
      gitee:
        client-id: ${OAUTH_GITEE_CLIENT_ID:}
        client-secret: ${OAUTH_GITEE_CLIENT_SECRET:}
      github:
        client-id: ${OAUTH_GITHUB_CLIENT_ID:}
        client-secret: ${OAUTH_GITHUB_CLIENT_SECRET:}
      dingtalk:
        client-id: ${OAUTH_DINGTALK_CLIENT_ID:}
        client-secret: ${OAUTH_DINGTALK_CLIENT_SECRET:}
      wechat:
        client-id: ${OAUTH_WECHAT_CLIENT_ID:}
        client-secret: ${OAUTH_WECHAT_CLIENT_SECRET:}
      qq:
        client-id: ${OAUTH_QQ_CLIENT_ID:}
        client-secret: ${OAUTH_QQ_CLIENT_SECRET:}
```

| 配置项 | 说明 |
| ---- | ---- |
| `redirect-uri` | 全局回调地址，全平台共用；必须与平台侧登记的一致 |
| `state-ttl-seconds` | state 有效期（秒），默认 300，下限 30。用户从跳授权页到回调超过该时长即失效（1021） |
| `providers.*.client-id` | 留空 = 该平台未启用，接口与登录页均不暴露 |
| provider key | 固定为 `gitee` / `github` / `dingtalk` / `wechat` / `qq`，顺序即前端图标展示顺序 |

数据库 `sys_user_oauth.provider` 存的是**大写枚举名**（`GITEE` / `GITHUB` / `DINGTALK` / `WECHAT_OPEN` / `QQ`），与配置里的 key 不是同一套取值，排查问题时注意区分。

---

## 六、启用后验证

1. **平台列表接口**（免登录，可直接浏览器访问）：

   ```bash
   curl http://localhost:8080/api/v1/auth/oauth/providers
   ```

   返回已启用平台，例如 `[{"key":"gitee","displayName":"Gitee"}]`。返回空数组说明凭证没生效（环境变量未注入或拼写错误）。

2. **登录页**：第三方图标区出现已启用平台；未配置的平台不显示。

3. **绑定**：账号密码登录 → 个人中心 → 第三方账号 → 点「绑定」→ 平台授权 → 回到个人中心显示已绑定（头像 + 昵称 + 绑定时间）。

4. **登录**：登出后回到登录页 → 点第三方图标 → 授权 → 直接进入面板；未绑定的第三方账号会提示「尚未绑定面板账号」并退回登录页。

5. **审计**：绑定/解绑写入 `sys_audit_log`（解绑标记 risky，前端展示「高危」标签）；第三方登录成功/失败写入 `sys_login_log`。

---

## 七、故障排查

### 错误码对照

| 码 | 含义 | 常见原因与处理 |
| ---- | ---- | ---- |
| 1020 | 平台不支持或未启用 | provider key 拼写错误，或该平台 client-id 未配置 |
| 1021 | state 失效或已被使用 | 超过 300 秒未回调、重复提交同一次回调、Redis 被清。重新发起即可 |
| 1022 | 第三方账号未绑定 | 正常拦截：该第三方身份没有绑定记录。先用账号密码登录并绑定 |
| 1023 | 该第三方账号已被其他面板账号绑定 | 一个第三方身份只能绑一个面板账号（`uk_provider_openid`）。先解绑原账号 |
| 1024 | 当前账号已绑定该平台 | 一人一平台只绑一条（`uk_user_provider`）。先解绑再重绑 |
| 1025 | 换取令牌或用户信息异常 | code 已被用过、client-secret 错误、回调地址不匹配、平台接口变更（钉钉旧接口属此类） |

### 常见问题

**登录页不显示第三方图标**
先查 `/api/v1/auth/oauth/providers` 是否返回空。环境变量注入后必须**重启后端**（不是热加载）；systemd 下改完记得 `daemon-reload`。

**平台报 `redirect_uri mismatch`**
回调地址与平台侧登记的不一致，逐字比对：协议（http/https）、域名、端口、`/#/` 有无、末尾斜杠。

**授权完跳回登录页没反应**
多半是回调地址带/不带 `#` 与路由模式不匹配，导致回调页路由没被匹配上。按第三节重新核对。

**`1025` 且日志显示平台返回错误**
用 `curl` 直接打平台 token 端点验证凭证是否有效；钉钉优先怀疑第四节的旧接口问题。

**state 频繁失效**
`state-ttl-seconds` 调大（如 600）；同时确认 Redis 正常——state 存 Redis，Redis 不可用则该功能整体不可用。

---

## 八、安全与运维

- **凭证**：只走环境变量，禁止写入配置文件、YAML 注释或数据库；轮换 secret 时先改环境变量再重启
- **HTTPS**：生产必须 HTTPS，否则 code 与 token 在链路上明文暴露
- **解绑策略**：解绑无额外限制——账号密码登录始终可用，不存在「解绑后彻底进不去」的失联风险；解绑操作记审计且标记高危
- **会话**：第三方登录成功后建立的是标准 Sa-Token 会话，与密码登录完全同构，受同一套超时/踢下线/并发策略约束
- **最小暴露**：只有配置了凭证的平台才会出现在 `providers` 接口与登录页；不用的平台保持留空即可，不必删代码
