-- ============================================================================
-- V14  JWT 工具（日常工具 -> 605）
--
-- 一级菜单「日常工具」（600，V13 已建）下新增叶子菜单：
--   605  JWT 工具  /tools/jwt        tools:jwt:list
--   6051 按钮权限  执行验签/签发      tools:jwt:exec
--
-- 说明：Header/Payload 解码、声明展示、exp/nbf 时间校验由前端完成（不需要密钥，
--       零后端调用）；只有「验签」与「签发」这两个需要密钥的动作走后端接口，
--       因此按 602/603 的先例只生成菜单权限，不额外生成按钮权限以外的行。
-- ============================================================================

-- ========== 1) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (605, 600, 'JWT 工具', 'C', '/tools/jwt', '/tools/jwt/index', 'tools:jwt:list', 'lucide:key-round', 5, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

-- ========== 2) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6051, 605, '执行验签/签发', 'F', NULL, NULL, 'tools:jwt:exec', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (605, 6051);
