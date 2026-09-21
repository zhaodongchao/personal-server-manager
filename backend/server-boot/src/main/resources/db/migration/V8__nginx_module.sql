-- ============================================================
-- ServerPanel Nginx 管理模块（运维工具）
-- 作者: zhaodc   创建时间: 2026-09-21
-- 说明：只做加法（新建 6 张表 + 菜单 + 权限点），对现有数据零破坏。
--       对应设计文档 psm/design/nginx-management-design.md
-- ============================================================

CREATE TABLE IF NOT EXISTS ops_nginx_instance (
  id            BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花 ID',
  name          VARCHAR(64)  NOT NULL COMMENT '实例名',
  detect_mode   VARCHAR(16)  NOT NULL DEFAULT 'auto' COMMENT 'auto/manual',
  binary_path   VARCHAR(255) NULL COMMENT 'nginx 可执行路径',
  prefix        VARCHAR(255) NULL COMMENT '--prefix',
  conf_path     VARCHAR(255) NULL COMMENT '主配置 nginx.conf 绝对路径',
  managed_dir   VARCHAR(255) NULL COMMENT '面板托管站点目录',
  stream_dir    VARCHAR(255) NULL COMMENT 'stream 托管目录',
  cert_dir      VARCHAR(255) NULL COMMENT '证书目录',
  acme_webroot  VARCHAR(255) NULL COMMENT 'ACME HTTP-01 webroot',
  log_dir       VARCHAR(255) NULL COMMENT '日志目录',
  default_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认实例',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  remark        VARCHAR(255) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) COMMENT 'Nginx 实例';

CREATE TABLE IF NOT EXISTS ops_nginx_upstream (
  id            BIGINT       NOT NULL PRIMARY KEY,
  instance_id   BIGINT       NOT NULL,
  name          VARCHAR(64)  NOT NULL,
  strategy      VARCHAR(16)  NOT NULL DEFAULT 'round_robin' COMMENT 'round_robin/least_conn/ip_hash',
  servers_json  TEXT         NOT NULL COMMENT '[{host,port,weight,max_fails,backup}]',
  keepalive     INT          NOT NULL DEFAULT 0,
  remark        VARCHAR(255) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_inst_name (instance_id, name)
) COMMENT 'Nginx 上游组';

CREATE TABLE IF NOT EXISTS ops_nginx_cert (
  id            BIGINT       NOT NULL PRIMARY KEY,
  instance_id   BIGINT       NOT NULL,
  domain        VARCHAR(255) NOT NULL,
  type          VARCHAR(16)  NOT NULL DEFAULT 'custom' COMMENT 'letsencrypt/custom',
  cert_path     VARCHAR(255) NULL,
  key_path      VARCHAR(255) NULL,
  issuer        VARCHAR(128) NULL,
  not_before    DATETIME     NULL,
  not_after     DATETIME     NULL,
  auto_renew    TINYINT      NOT NULL DEFAULT 0,
  last_renew_at DATETIME     NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'valid' COMMENT 'valid/expiring/expired/pending',
  remark        VARCHAR(255) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_inst (instance_id)
) COMMENT 'Nginx 证书';

CREATE TABLE IF NOT EXISTS ops_nginx_site (
  id              BIGINT       NOT NULL PRIMARY KEY,
  instance_id     BIGINT       NOT NULL,
  name            VARCHAR(64)  NOT NULL,
  domains         VARCHAR(512) NOT NULL COMMENT '逗号分隔，首为主域',
  site_type       VARCHAR(16)  NOT NULL DEFAULT 'proxy' COMMENT 'proxy/static',
  upstream_id     BIGINT       NULL,
  upstream_inline VARCHAR(512) NULL,
  static_root     VARCHAR(255) NULL,
  ssl_mode        VARCHAR(16)  NOT NULL DEFAULT 'off' COMMENT 'off/letsencrypt/custom',
  cert_id         BIGINT       NULL,
  http_redirect   TINYINT      NOT NULL DEFAULT 1,
  hsts            TINYINT      NOT NULL DEFAULT 0,
  locations_json  TEXT         NULL COMMENT '自定义 location 列表',
  conf_path       VARCHAR(255) NULL,
  conf_hash       VARCHAR(64)  NULL,
  status          TINYINT      NOT NULL DEFAULT 1,
  remark          VARCHAR(255) NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_inst (instance_id)
) COMMENT 'Nginx 站点';

CREATE TABLE IF NOT EXISTS ops_nginx_stream (
  id            BIGINT       NOT NULL PRIMARY KEY,
  instance_id   BIGINT       NOT NULL,
  name          VARCHAR(64)  NOT NULL,
  protocol      VARCHAR(8)   NOT NULL DEFAULT 'tcp' COMMENT 'tcp/udp',
  listen_port   INT          NOT NULL,
  upstream_host VARCHAR(255) NOT NULL,
  upstream_port INT          NOT NULL,
  proxy_timeout INT          NOT NULL DEFAULT 600,
  conf_path     VARCHAR(255) NULL,
  conf_hash     VARCHAR(64)  NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  remark        VARCHAR(255) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_inst (instance_id)
) COMMENT 'Nginx 四层转发';

CREATE TABLE IF NOT EXISTS ops_nginx_change (
  id            BIGINT       NOT NULL PRIMARY KEY,
  instance_id   BIGINT       NOT NULL,
  op            VARCHAR(24)  NOT NULL COMMENT 'CREATE_SITE/UPDATE_SITE/DELETE_SITE/TOGGLE/ISSUE_CERT/RENEW_CERT/RELOAD/...',
  target_type   VARCHAR(32)  NULL,
  target_id     BIGINT       NULL,
  conf_path     VARCHAR(255) NULL COMMENT '受影响的配置文件绝对路径',
  before_conf   MEDIUMTEXT   NULL,
  after_conf    MEDIUMTEXT   NULL,
  rollback_conf MEDIUMTEXT   NULL,
  rolled_back   TINYINT      NOT NULL DEFAULT 0,
  result        TINYINT      NOT NULL DEFAULT 0 COMMENT '0成功1失败',
  error_msg     VARCHAR(500) NULL,
  operator      VARCHAR(64)  NULL,
  operator_ip   VARCHAR(64)  NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_inst (instance_id),
  KEY idx_created (created_at)
) COMMENT 'Nginx 变更快照';

-- ========== 菜单与权限点 ==========
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at) VALUES
  (406, 400, 'Nginx 管理', 'C', '/ops/nginx', '/ops/nginx/index', 'ops:nginx:list', 'lucide:server-cog', 5, 1, 1, NOW(), NOW());

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at) VALUES
  (4061, 406, '站点写操作', 'F', NULL, NULL, 'ops:nginx:site:write', NULL, 1, 1, 1, NOW(), NOW()),
  (4062, 406, '证书管理',   'F', NULL, NULL, 'ops:nginx:cert',       NULL, 2, 1, 1, NOW(), NOW()),
  (4063, 406, '重载与校验', 'F', NULL, NULL, 'ops:nginx:reload',     NULL, 3, 1, 1, NOW(), NOW()),
  (4064, 406, '回滚操作',   'F', NULL, NULL, 'ops:nginx:rollback',   NULL, 4, 1, 1, NOW(), NOW()),
  (4065, 406, '实例配置',   'F', NULL, NULL, 'ops:nginx:instance',   NULL, 5, 1, 1, NOW(), NOW()),
  (4066, 406, '四层转发',   'F', NULL, NULL, 'ops:nginx:stream',     NULL, 6, 1, 1, NOW(), NOW());

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 406), (1, 4061), (1, 4062), (1, 4063), (1, 4064), (1, 4065), (1, 4066);
