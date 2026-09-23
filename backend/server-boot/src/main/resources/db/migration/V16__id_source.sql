-- ============================================================================
-- V16  ID 取号数据源（应用栈 -> 数据库 -> 取号数据源）
--
-- 背景：V15 的「ID 生成器」把第一类方案（数据库原生自增 / 序列）做成了参数化推演，
--       不连库。本次改为真连库取号，为此需要一张登记表保存目标库的连接信息，
--       并在「应用栈 -> 数据库」（503）下补齐按钮权限。
--
-- 为什么归口应用栈而不是日常工具：它管理的是「数据库连接」这一应用栈领域的资源，
--       与「面板代管 MySQL 库」（app_database）同类。ID 生成器通过 SPI 消费它，
--       而不是自己持有连接信息（否则 server-tools 就得依赖 server-appstack）。
--
-- 安全前提（对应 application.yml 的 serverpanel.id-source）：
--   * 面板自身库与生产库在代码里是硬黑名单，写进配置也放不开；
--   * 允许的库默认只有专用库 psm_tools；
--   * 自动创建的序列 / 自增表必须带 psm_ 前缀；
--   * 口令以 AES-256-GCM 密文存储，密钥来自 serverpanel.secret.key。
-- ============================================================================

-- ========== 1) 取号数据源表 ==========
CREATE TABLE IF NOT EXISTS app_id_source (
  id              BIGINT       NOT NULL                COMMENT '主键（应用侧雪花 ID）',
  name            VARCHAR(60)  NOT NULL                COMMENT '数据源名称',
  db_type         VARCHAR(20)  NOT NULL                COMMENT '库类型：POSTGRESQL / MYSQL',
  host            VARCHAR(120) NOT NULL                COMMENT '主机',
  port            INT          NOT NULL                COMMENT '端口',
  db_name         VARCHAR(64)  NOT NULL                COMMENT '目标库（受库黑名单与允许列表约束）',
  username        VARCHAR(64)  NOT NULL                COMMENT '账号',
  password_cipher VARCHAR(512) NOT NULL                COMMENT '口令密文（AES-256-GCM，v1: 前缀）',
  table_name      VARCHAR(64)  DEFAULT NULL            COMMENT '自增表名（MySQL 侧，空则用 psm_id_demo）',
  sequence_name   VARCHAR(64)  DEFAULT NULL            COMMENT '序列名（PostgreSQL 侧，空则用 psm_id_seq）',
  auto_init       TINYINT      NOT NULL DEFAULT 1      COMMENT '取号前是否自动初始化取号对象：1 是 / 0 否',
  status          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 启用 / 0 停用',
  remark          VARCHAR(200) DEFAULT NULL            COMMENT '备注',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_id_source_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '取号数据源（ID 生成器「自增/序列」真连库取号）';

-- ========== 2) 按钮/功能权限（挂在「应用栈 -> 数据库」503 下）==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (5034, 503, '取号数据源保存', 'F', NULL, NULL, 'appstack:id-source:save',   NULL, 4, 1, 1, NOW(), NOW()),
  (5035, 503, '取号数据源删除', 'F', NULL, NULL, 'appstack:id-source:delete', NULL, 5, 1, 1, NOW(), NOW()),
  (5036, 503, '测试连接与初始化', 'F', NULL, NULL, 'appstack:id-source:probe',  NULL, 6, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), sort = VALUES(sort), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (5034, 5035, 5036);
