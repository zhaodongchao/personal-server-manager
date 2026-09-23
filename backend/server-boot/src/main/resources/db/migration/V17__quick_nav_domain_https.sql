-- =============================================================================
-- ServerPanel V17__quick_nav_domain_https.sql
-- 快捷导航补充「访问域名」与「是否 https」两列：
-- 原先工作台只能按 http://<面板访问域名>:<port><path> 拼地址，
-- 反向代理 / 独立域名托管（如 git.example.com）无法正确跳转。
--
-- 语义：
--   domain 为空 -> 沿用面板当前访问域名（浏览器 hostname）
--   domain 非空 -> 用该域名（可含端口，如 1.2.3.4:8080）
--   https=1     -> 使用 https 协议
--   端口拼接统一由前端处理：域名场景跳过协议默认端口（http 80 / https 443）
-- =============================================================================

ALTER TABLE sys_quick_nav
  ADD COLUMN domain VARCHAR(120) NOT NULL DEFAULT '' COMMENT '访问域名/主机，留空则用面板当前访问域名',
  ADD COLUMN https  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 https 0 http';

-- 已有记录保持「跟随面板域名 + http」的旧行为
UPDATE sys_quick_nav SET domain = '', https = 0;
