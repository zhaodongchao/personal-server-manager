-- =============================================================================
-- ServerPanel V3__add_monitor_menu.sql
-- 工作台改造：原"仪表盘"页面改为"工作台"；新增"服务器监控"菜单（保留监控功能）
-- =============================================================================

-- 原"仪表盘"改为"工作台"
UPDATE sys_menu SET menu_name = '工作台' WHERE id = 100;

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status)
VALUES (405, 400, '服务器监控', 'C', '/monitor', '/monitor/index', 'dashboard:view', 'lucide:gauge', 5, 1, 1);

-- 超级管理员拥有全部菜单（迁移历史库需单独授权）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 405);
