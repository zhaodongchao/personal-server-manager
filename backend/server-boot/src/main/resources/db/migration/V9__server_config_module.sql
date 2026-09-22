-- ============================================================
-- ServerPanel 服务器配置管理模块（运维工具）
-- 作者: zhaodc   创建时间: 2026-09-22
-- 说明：本模块的数据（配置类别 / 配置项 / 变更历史）全部存放在 MongoDB，
--       本迁移只负责 MySQL 侧的菜单与权限点，纯加法，对现有数据零破坏。
--       对应设计文档 psm/design/server-config-design.md
-- ============================================================

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at) VALUES
  (407, 400, '服务器配置', 'C', '/ops/server-config', '/ops/server-config/index', 'ops:config:list', 'lucide:sliders-horizontal', 6, 1, 1, NOW(), NOW());

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at) VALUES
  (4071, 407, '配置生效',      'F', NULL, NULL, 'ops:config:apply',    NULL, 1, 1, 1, NOW(), NOW()),
  (4072, 407, '配置恢复/回滚', 'F', NULL, NULL, 'ops:config:rollback', NULL, 2, 1, 1, NOW(), NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 407), (1, 4071), (1, 4072);
