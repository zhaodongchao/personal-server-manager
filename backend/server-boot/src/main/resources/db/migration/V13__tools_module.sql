-- ============================================================================
-- V13  日常工具模块（server-tools）
--
-- 一级菜单「日常工具」+ 4 个叶子菜单。
-- 编号沿用既有约定：一级菜单按百位（100 概览 / 200 系统管理 / 300 文件管理 /
-- 400 运维工具 / 500 应用栈 / 600 日常工具），叶子按 60X，按钮权限按 60XN。
--
-- 注意：URL 编解码（602）与图片 Base64 互转（603）是纯前端能力，没有后端接口，
--       因此只有菜单权限（:list），不生成按钮级权限行。
-- ============================================================================

-- ========== 1) 一级菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (600, 0, '日常工具', 'M', '/tools', NULL, NULL, 'lucide:tool-case', 5, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name), route_path = VALUES(route_path),
  icon = VALUES(icon), sort = VALUES(sort), updated_at = NOW();

-- ========== 2) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (601, 600, '字符串加密解密', 'C', '/tools/string-crypto', '/tools/string-crypto/index', 'tools:crypto:list',    'lucide:lock',     1, 1, 1, NOW(), NOW()),
  (602, 600, 'URL 加密解密',   'C', '/tools/url-codec',     '/tools/url-codec/index',     'tools:url:list',       'lucide:link',     2, 1, 1, NOW(), NOW()),
  (603, 600, '图片与Base64互转', 'C', '/tools/image-base64', '/tools/image-base64/index', 'tools:image:list',     'lucide:image',    3, 1, 1, NOW(), NOW()),
  (604, 600, '混淆加密解密',   'C', '/tools/obfuscate',     '/tools/obfuscate/index',     'tools:obfuscate:list', 'lucide:shuffle',  4, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name), route_path = VALUES(route_path), component = VALUES(component),
  perms = VALUES(perms), icon = VALUES(icon), sort = VALUES(sort), updated_at = NOW();

-- ========== 3) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6011, 601, '执行加解密', 'F', NULL, NULL, 'tools:crypto:exec',    NULL, 1, 1, 1, NOW(), NOW()),
  (6041, 604, '执行混淆',   'F', NULL, NULL, 'tools:obfuscate:exec', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name), perms = VALUES(perms), updated_at = NOW();

-- ========== 4) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (600, 601, 602, 603, 604, 6011, 6041);
