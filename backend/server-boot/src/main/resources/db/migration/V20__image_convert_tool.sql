-- ============================================================================
-- V20  图片转换工具（日常工具 -> 609）
--
-- 一级菜单「日常工具」（600，V13 已建）下新增叶子菜单：
--   609   图片转换  /tools/image-convert  tools:image:list
--   6091  执行图片转换（按钮权限）            tools:image:convert
--
-- 纯内存计算（解码 -> 缩放 -> 重编码），不落盘、不建业务表；
-- 所有幂等性由 ON DUPLICATE KEY UPDATE / INSERT IGNORE 保证。
-- ============================================================================

-- ========== 1) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (609, 600, '图片转换', 'C', '/tools/image-convert', '/tools/image-convert/index', 'tools:image:list', 'lucide:image', 9, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

-- ========== 2) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6091, 609, '执行图片转换', 'F', NULL, NULL, 'tools:image:convert', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (609, 6091);
