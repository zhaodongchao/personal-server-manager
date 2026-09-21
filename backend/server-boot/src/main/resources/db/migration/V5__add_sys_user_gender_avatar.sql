-- =============================================================================
-- ServerPanel V5__add_sys_user_gender_avatar.sql
-- 用户表扩展：性别 + 头像（三态）
--
-- 头像 avatar 由 VARCHAR(255) 升级为 MEDIUMTEXT，承载三种取值：
--   NULL / ''                    → 使用系统默认头像 /avatar.svg
--   preset:N  (N=1..8)           → 使用内置预设头像，由前端本地静态资源渲染
--   data:image/xxx;base64,....   → 手动上传的自定义头像，base64 直接落库
--
-- 同时新增 avatar_updated_at：头像最后变更时间，供前端做缓存失效判断。
-- =============================================================================

ALTER TABLE sys_user
  ADD COLUMN gender TINYINT NOT NULL DEFAULT 0
      COMMENT '性别 0未知 1男 2女' AFTER nickname,
  ADD COLUMN avatar_updated_at DATETIME DEFAULT NULL
      COMMENT '头像最后更新时间' AFTER `desc`,
  MODIFY COLUMN avatar MEDIUMTEXT NULL
      COMMENT '头像：NULL/空=默认；preset:N=内置预设；data:image/...;base64,...=自定义上传';
