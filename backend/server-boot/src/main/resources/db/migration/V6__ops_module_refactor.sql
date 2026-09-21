-- ============================================================
-- 运维工具三模块重构：计划任务增强 + 防火墙变更快照 + 宿主通道权限点
--
-- 作者: zhaodc        创建时间: 2026-09-21
--
-- 说明：本迁移**只做加法**（新增列全部带默认值、新增表、新增权限点），
--       对现有数据零破坏。迁移前 ops_cron_job / ops_cron_log 均为 0 行。
--       对应设计文档 psm/design/ops-tools-refactor-design.md 第八节。
-- ============================================================

-- ========== 1) 计划任务表增强（S2） ==========
ALTER TABLE ops_cron_job
  ADD COLUMN human_expr       VARCHAR(120) NULL         COMMENT 'cron 表达式人话描述（缓存）' AFTER cron_expr,
  ADD COLUMN misfire_policy   VARCHAR(16)  NOT NULL DEFAULT 'skip' COMMENT '错过执行策略 skip/run_once/catch_up' AFTER status,
  ADD COLUMN overlap_policy   VARCHAR(16)  NOT NULL DEFAULT 'skip' COMMENT '并发策略 skip/queue/parallel' AFTER misfire_policy,
  ADD COLUMN fail_count       INT          NOT NULL DEFAULT 0    COMMENT '连续失败次数（成功归零）' AFTER overlap_policy,
  ADD COLUMN max_fail         INT          NOT NULL DEFAULT 0    COMMENT '连续失败自动停用阈值，0=不自动停用' AFTER fail_count,
  ADD COLUMN last_exit_code   INT          NULL                  COMMENT '最近一次退出码（-2=超时）' AFTER max_fail,
  ADD COLUMN last_duration_ms BIGINT       NULL                  COMMENT '最近一次耗时毫秒' AFTER last_exit_code,
  ADD COLUMN running          TINYINT      NOT NULL DEFAULT 0    COMMENT '1=执行中（并发互斥）' AFTER last_duration_ms,
  ADD COLUMN running_log_id   BIGINT       NULL                  COMMENT '当前运行对应的日志 ID' AFTER running,
  ADD COLUMN lock_until       DATETIME     NULL                  COMMENT '调度抢锁到期时间（多实例安全）' AFTER running_log_id;

ALTER TABLE ops_cron_job ADD INDEX idx_status_next_run (status, next_run_at);

-- ========== 2) 执行日志增强（S2） ==========
ALTER TABLE ops_cron_log
  ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'cron' COMMENT 'cron/manual/retry' AFTER job_name,
  ADD COLUMN operator     VARCHAR(64) NULL     COMMENT '手动触发者' AFTER trigger_type,
  ADD COLUMN timed_out    TINYINT     NOT NULL DEFAULT 0 COMMENT '1=超时被终止' AFTER exit_code,
  ADD COLUMN truncated    TINYINT     NOT NULL DEFAULT 0 COMMENT '1=输出被截断' AFTER timed_out;

ALTER TABLE ops_cron_log ADD INDEX idx_started_at (started_at);

-- ========== 3) 防火墙变更快照（S3，支持审计与回滚） ==========
CREATE TABLE IF NOT EXISTS ops_firewall_change (
  id               BIGINT       NOT NULL PRIMARY KEY       COMMENT '雪花 ID',
  backend          VARCHAR(16)  NOT NULL                   COMMENT 'ufw/firewalld',
  op               VARCHAR(24)  NOT NULL                   COMMENT 'ADD_RULE/DELETE_RULE/ENABLE/DISABLE/SET_DEFAULT/RELOAD',
  rule_desc        VARCHAR(255) NOT NULL                   COMMENT '本次变更的规则描述',
  before_snapshot  MEDIUMTEXT   NULL                       COMMENT '变更前 ufw status numbered 原文',
  after_snapshot   MEDIUMTEXT   NULL                       COMMENT '变更后 ufw status numbered 原文',
  diff_json        TEXT         NULL                       COMMENT '结构化差异（新增/删除的规则）',
  undo_json        TEXT         NULL                       COMMENT '回滚脚本（代理 op 列表，供看门狗到期重放）',
  guard_ack        TINYINT      NOT NULL DEFAULT 0         COMMENT '是否经过 L3 二次确认',
  watchdog_seconds INT          NULL                       COMMENT '看门狗窗口秒数，NULL=未启用',
  rollbackable     TINYINT      NOT NULL DEFAULT 1         COMMENT '是否可回滚',
  rolled_back      TINYINT      NOT NULL DEFAULT 0         COMMENT '是否已回滚',
  result           TINYINT      NOT NULL DEFAULT 0         COMMENT '0 成功 1 失败',
  error_msg        VARCHAR(500) NULL                       COMMENT '失败原因',
  operator         VARCHAR(64)  NULL                       COMMENT '操作人',
  operator_ip      VARCHAR(64)  NULL                       COMMENT '来源 IP',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_created_at (created_at)
) COMMENT '防火墙变更快照与回滚记录';

-- ========== 4) 权限点补充 ==========
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at) VALUES
  (4022, 402, '查看服务日志',   'F', NULL, NULL, 'ops:service:log',     NULL, 2, 1, 1, NOW(), NOW()),
  (4023, 402, '批量服务操作',   'F', NULL, NULL, 'ops:service:batch',   NULL, 3, 1, 1, NOW(), NOW()),
  (4024, 402, '危险服务操作',   'F', NULL, NULL, 'ops:service:danger',  NULL, 4, 1, 1, NOW(), NOW()),
  (4035, 403, '任务启停开关',   'F', NULL, NULL, 'ops:cron:status',     NULL, 5, 1, 1, NOW(), NOW()),
  (4036, 403, '任务日志清理',   'F', NULL, NULL, 'ops:cron:log-clean',  NULL, 6, 1, 1, NOW(), NOW()),
  (4042, 404, '防火墙回滚',     'F', NULL, NULL, 'ops:firewall:rollback', NULL, 2, 1, 1, NOW(), NOW()),
  (4043, 404, '防火墙危险操作', 'F', NULL, NULL, 'ops:firewall:danger',   NULL, 3, 1, 1, NOW(), NOW());

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 4022), (1, 4023), (1, 4024), (1, 4035), (1, 4036), (1, 4042), (1, 4043);

-- ========== 5) 菜单名调整（与用户表述一致） ==========
UPDATE sys_menu SET menu_name = '计划管理', updated_at = NOW() WHERE id = 403;
