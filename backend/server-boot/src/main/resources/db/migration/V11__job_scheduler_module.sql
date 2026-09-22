-- ============================================================
-- 新增「定时任务管理 / 定时任务日志」（应用栈）模块
--
-- 作者: zhaodc        创建时间: 2026-09-22
--
-- 背景：参考 xxl-job 的「调度中心 / 执行器」分层与「调度日志双段」模型，为面板提供
--       可在界面上运维、可观测、可回滚的定时任务能力。
--       设计文档：psm/design/job-scheduler-design.md
--
-- 内容：
--   1) 建表 app_executor / app_job / app_job_log
--   2) seed 内置执行器 serverpanel-builtin（id=1，不可删除、不可停用）
--   3) 菜单 504 定时任务管理 / 505 定时任务日志 + 8 个按钮权限点
--   4) 授予 role_id = 1
--
-- 本迁移为**纯加法**，不改动任何历史迁移（Flyway 校验和不可变）。
-- 回滚粒度：git revert 提交 + 手工 DROP 三张表 + 删除菜单，无需 down 迁移。
-- ============================================================

-- ========== 1) 执行器注册表 ==========
CREATE TABLE IF NOT EXISTS app_executor (
  id            BIGINT       NOT NULL COMMENT '主键(雪花ID,字符串序列化)',
  app_name      VARCHAR(64)  NOT NULL COMMENT '执行器AppName(唯一)',
  executor_name VARCHAR(64)           DEFAULT NULL COMMENT '执行器显示名',
  type          VARCHAR(16)  NOT NULL COMMENT 'BUILTIN 内置 / HTTP 外部',
  base_url      VARCHAR(255)          DEFAULT NULL COMMENT '外部执行器基地址,如 http://10.0.0.5:9999',
  auth_token    VARCHAR(255)          DEFAULT NULL COMMENT '外部执行器鉴权令牌(出参一律掩码)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE / UNREACHABLE / DISABLED',
  fail_streak   INT          NOT NULL DEFAULT 0 COMMENT '连续探测/调用失败次数(熔断计数)',
  last_beat_at  DATETIME              DEFAULT NULL COMMENT '最近一次探测成功时间',
  last_error    VARCHAR(500)          DEFAULT NULL COMMENT '最近一次失败原因',
  remark        VARCHAR(200)          DEFAULT NULL COMMENT '备注',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_name (app_name),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务执行器';

-- ========== 2) 任务定义 ==========
CREATE TABLE IF NOT EXISTS app_job (
  id              BIGINT       NOT NULL COMMENT '主键(雪花ID,字符串序列化)',
  job_name        VARCHAR(64)  NOT NULL COMMENT '任务名(唯一)',
  job_desc        VARCHAR(200)          DEFAULT NULL COMMENT '任务描述',
  executor_id     BIGINT       NOT NULL COMMENT '执行器ID -> app_executor.id',
  handler         VARCHAR(16)  NOT NULL COMMENT 'SHELL / HTTP / SERVICE / INTERNAL',
  handler_param   JSON                  DEFAULT NULL COMMENT '处理器参数(按 handler 各自 schema)',
  cron_expr       VARCHAR(64)  NOT NULL COMMENT 'cron(6段:秒 分 时 日 月 周)',
  route_strategy  VARCHAR(24)  NOT NULL DEFAULT 'FIRST'  COMMENT 'FIRST/ROUND/RANDOM/FAILOVER',
  block_strategy  VARCHAR(24)  NOT NULL DEFAULT 'SERIAL' COMMENT 'SERIAL/DISCARD_LATER/COVER_EARLY',
  timeout_sec     INT          NOT NULL DEFAULT 300 COMMENT '执行超时秒数(上限900)',
  retry_count     INT          NOT NULL DEFAULT 0   COMMENT '失败重试次数(0~3)',
  status          TINYINT      NOT NULL DEFAULT 0   COMMENT '0停用 1启用',
  confirm_keyword VARCHAR(32)           DEFAULT NULL COMMENT 'L3任务需校验的确认关键字',
  next_fire_time  DATETIME              DEFAULT NULL COMMENT '下次触发时间(调度器回写,列表展示)',
  last_fire_time  DATETIME              DEFAULT NULL COMMENT '上次触发时间',
  last_status     VARCHAR(16)           DEFAULT NULL COMMENT '上次结果 RUNNING/SUCCESS/FAILED/TIMEOUT/DISCARDED/KILLED',
  fail_streak     INT          NOT NULL DEFAULT 0   COMMENT '连续失败次数(列表告警用)',
  owner           BIGINT                DEFAULT NULL COMMENT '创建人 user_id',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_name (job_name),
  KEY idx_executor (executor_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务';

-- ========== 3) 调度与执行日志（双段：trigger_* 调度侧 / handle_* 执行侧） ==========
-- 语义要点：trigger_code=500 表示「到点了但没能派发出去」（执行器不可达 / 被阻塞策略丢弃），
--           handle_code=500 表示「派发出去了但执行失败」。两者必须可分辨 —— 这是
--           xxl-job 日志模型的核心价值，糊成一列就丢掉了整套设计的意义。
CREATE TABLE IF NOT EXISTS app_job_log (
  id              BIGINT       NOT NULL COMMENT '主键(雪花ID,字符串序列化)',
  job_id          BIGINT       NOT NULL COMMENT '任务ID',
  job_name        VARCHAR(64)  NOT NULL COMMENT '任务名快照(任务删除后日志仍可读)',
  executor_app_name VARCHAR(64)         DEFAULT NULL COMMENT '执行器AppName快照',
  executor_address  VARCHAR(255)        DEFAULT NULL COMMENT '实际派发地址(local 或 外部URL)',
  handler         VARCHAR(16)  NOT NULL COMMENT '处理器快照',
  trigger_type    VARCHAR(16)  NOT NULL COMMENT 'CRON 自动 / MANUAL 手动',
  trigger_time    DATETIME     NOT NULL COMMENT '调度时间',
  trigger_code    INT          NOT NULL DEFAULT 0 COMMENT '0已派发 / 500派发失败 / 404任务已不存在',
  trigger_msg     VARCHAR(1000)         DEFAULT NULL COMMENT '调度说明(阻塞丢弃/执行器不可达等)',
  handle_time     DATETIME              DEFAULT NULL COMMENT '开始执行时间',
  handle_code     INT                   DEFAULT NULL COMMENT '0成功 / 500失败',
  handle_msg      VARCHAR(1000)         DEFAULT NULL COMMENT '执行结果摘要',
  handle_duration_ms BIGINT             DEFAULT NULL COMMENT '执行耗时(毫秒)',
  status          VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/TIMEOUT/DISCARDED/KILLED',
  retry_index     INT          NOT NULL DEFAULT 0 COMMENT '第几次尝试(0为首次)',
  executor_output TEXT                  DEFAULT NULL COMMENT '执行输出全文(截断64KB)',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_job_time (job_id, trigger_time DESC),
  KEY idx_status_time (status, trigger_time DESC),
  KEY idx_trigger_time (trigger_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务调度日志';

-- ========== 4) seed 内置执行器（id=1 固定小 ID，便于识别与迁移） ==========
INSERT INTO app_executor
  (id, app_name, executor_name, type, base_url, status, fail_streak, remark, created_at, updated_at)
VALUES
  (1, 'serverpanel-builtin', '面板内置执行器', 'BUILTIN', 'local', 'AVAILABLE', 0,
   '面板自身；内置执行器不可删除、不可停用', NOW(), NOW())
ON DUPLICATE KEY UPDATE app_name = app_name;

-- ========== 5) 菜单与权限点 ==========
-- 说明：id 用 504 / 505，**不复用**已随 V10 退休的 502（同一 id 先后指两个功能会让
--       审计日志与历史数据产生歧义，与错误码「留空号不补」同一原则）。
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (504, 500, '定时任务管理', 'C', '/appstack/job',     '/appstack/job/index',     'appstack:job:list',    'lucide:calendar-clock', 3, 1, 1, NOW(), NOW()),
  (505, 500, '定时任务日志', 'C', '/appstack/job-log', '/appstack/job-log/index', 'appstack:joblog:list', 'lucide:scroll-text',    4, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();

INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (5041, 504, '新增/编辑任务', 'F', NULL, NULL, 'appstack:job:save',     NULL, 1, 1, 1, NOW(), NOW()),
  (5042, 504, '删除任务',      'F', NULL, NULL, 'appstack:job:delete',   NULL, 2, 1, 1, NOW(), NOW()),
  (5043, 504, '启用/停用',     'F', NULL, NULL, 'appstack:job:toggle',   NULL, 3, 1, 1, NOW(), NOW()),
  (5044, 504, '立即执行',      'F', NULL, NULL, 'appstack:job:run',      NULL, 4, 1, 1, NOW(), NOW()),
  (5045, 504, '停止执行',      'F', NULL, NULL, 'appstack:job:stop',     NULL, 5, 1, 1, NOW(), NOW()),
  (5046, 504, '执行器管理',    'F', NULL, NULL, 'appstack:job:executor', NULL, 6, 1, 1, NOW(), NOW()),
  (5047, 504, '危险任务确认',  'F', NULL, NULL, 'appstack:job:confirm',  NULL, 7, 1, 1, NOW(), NOW()),
  (5051, 505, '日志清理',      'F', NULL, NULL, 'appstack:joblog:clear', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- ========== 6) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (504, 505, 5041, 5042, 5043, 5044, 5045, 5046, 5047, 5051);
