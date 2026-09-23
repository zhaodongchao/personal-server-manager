-- ============================================================================
-- V15  ID 生成器（日常工具 -> 606）
--
-- 一级菜单「日常工具」（600，V13 已建）下新增叶子菜单：
--   606  ID 生成器   /tools/id        tools:id:list
--   6061 按钮权限    生成 / 反解 ID    tools:id:exec
--
-- 说明：方案清单、位段拆解、参数校验全部由后端下发与执行（生成动作必须放在后端 ——
--       雪花的机器号与序列号是进程态，时钟回拨也只有后端能真实演示），
--       因此与 601/604/605 一致：叶子菜单 + 一个执行权限。
-- ============================================================================

-- ========== 1) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (606, 600, 'ID 生成器', 'C', '/tools/id', '/tools/id/index', 'tools:id:list', 'lucide:hash', 6, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

-- ========== 2) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6061, 606, '生成/反解 ID', 'F', NULL, NULL, 'tools:id:exec', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (606, 6061);
