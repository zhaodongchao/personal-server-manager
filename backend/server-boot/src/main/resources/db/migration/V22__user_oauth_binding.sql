-- ============================================================================
-- V22  第三方登录绑定（登录页第三方登录 / 个人中心绑定管理）
--
-- 账号策略：先绑定后登录，不做自动注册 —— 第三方身份必须先由已登录用户
--           在个人中心主动绑定，之后才能用于登录页一键登录。
--
-- 约束：
--   * uk_user_provider   ：一人一平台只绑一条（想换账号须先解绑）；
--   * uk_provider_openid ：一个第三方身份只能绑定一个面板账号
--                           （防止同一 GitHub/Gitee 账号打开多个面板入口）。
--
-- 字段说明：
--   * provider 取值：GITEE / GITHUB / DINGTALK / WECHAT_OPEN / QQ
--     （与后端 OAuthProperties 的 provider key 对应，存大写枚举形式）；
--   * open_id 为 JustAuth AuthUser.uuid —— 各平台统一的用户唯一标识；
--   * union_id 仅为微信等跨应用体系保留，可空；
--   * nickname/avatar 为绑定时刻快照，便于个人中心展示，不随平台侧变化更新。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_user_oauth (
  id         BIGINT       NOT NULL                COMMENT '主键（应用侧雪花 ID）',
  user_id    BIGINT       NOT NULL                COMMENT '面板用户 ID（sys_user.id）',
  provider   VARCHAR(20)  NOT NULL                COMMENT 'OAuth 平台：GITEE/GITHUB/DINGTALK/WECHAT_OPEN/QQ',
  open_id    VARCHAR(128) NOT NULL                COMMENT '平台用户唯一标识（JustAuth AuthUser.uuid）',
  union_id   VARCHAR(128) DEFAULT NULL            COMMENT '平台 unionid（微信等跨应用标识，可空）',
  nickname   VARCHAR(128) DEFAULT NULL            COMMENT '第三方昵称（绑定时刻快照）',
  avatar     VARCHAR(512) DEFAULT NULL            COMMENT '第三方头像 URL（绑定时刻快照）',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_provider (user_id, provider),
  UNIQUE KEY uk_provider_openid (provider, open_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户第三方登录绑定';
