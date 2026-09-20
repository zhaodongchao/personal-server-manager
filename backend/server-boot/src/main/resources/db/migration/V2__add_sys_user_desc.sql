-- =============================================================================
-- ServerPanel V2__add_sys_user_desc.sql
-- 个人中心：用户表增加"个人简介"列（Vben 个人中心 基本设置 的 desc 字段落库）
-- =============================================================================

ALTER TABLE sys_user
  ADD COLUMN `desc` VARCHAR(255) DEFAULT NULL COMMENT '个人简介' AFTER avatar;
