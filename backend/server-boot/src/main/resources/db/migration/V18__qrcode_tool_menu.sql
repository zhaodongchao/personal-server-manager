-- ============================================================================
-- V18  二维码工具（日常工具 -> 607）
--
-- 一级菜单「日常工具」（600，V13 已建）下新增叶子菜单：
--   607   二维码工具   /tools/qrcode   tools:qrcode:list
--   6071  生成/识别二维码（按钮权限）  tools:qrcode:exec
--
-- 说明：三种码需要分清，接入后在页面上如实说明：
--   1) 通用二维码（文本/网址/WiFi/名片/……）本工具服务端生成，ZXing 离线绘制；
--   2) 「含小程序路径的二维码」同样是通用二维码，本工具可生成；
--   3) 微信官方小程序码（菊花朵码）与公众号带场景值二维码只能由微信服务端
--      API 生成，第三方无法本地算出 —— 不在本工具覆盖范围。
-- ============================================================================

-- ========== 1) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (607, 600, '二维码工具', 'C', '/tools/qrcode', '/tools/qrcode/index', 'tools:qrcode:list', 'lucide:qr-code', 7, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

-- ========== 2) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6071, 607, '生成/识别二维码', 'F', NULL, NULL, 'tools:qrcode:exec', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (607, 6071);
