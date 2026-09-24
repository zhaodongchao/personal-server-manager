-- ============================================================================
-- V19  正则表达式工具（日常工具 -> 608）
--
-- 一级菜单「日常工具」（600，V13 已建）下新增叶子菜单：
--   608   正则表达式工具  /tools/regex  tools:regex:list
--   6081  生成/测试/解析正则（按钮权限）      tools:regex:exec
--   6082  新增正则模板（按钮权限）            tools:regex:template:add
--   6083  编辑正则模板（按钮权限）            tools:regex:template:edit
--   6084  删除正则模板（按钮权限）            tools:regex:template:delete
--
-- 另建全局共享模板表 sys_regex_template：所有能打开本工具的用户可见，
-- 由具备 template:add/edit/delete 权限的用户维护；重名由唯一键兜底。
-- ============================================================================

-- ========== 1) 叶子菜单 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (608, 600, '正则表达式工具', 'C', '/tools/regex', '/tools/regex/index', 'tools:regex:list', 'lucide:regex', 8, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

-- ========== 2) 按钮/功能权限 ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6081, 608, '生成/测试/解析正则', 'F', NULL, NULL, 'tools:regex:exec',             NULL, 1, 1, 1, NOW(), NOW()),
  (6082, 608, '新增正则模板',       'F', NULL, NULL, 'tools:regex:template:add',    NULL, 2, 1, 1, NOW(), NOW()),
  (6083, 608, '编辑正则模板',       'F', NULL, NULL, 'tools:regex:template:edit',   NULL, 3, 1, 1, NOW(), NOW()),
  (6084, 608, '删除正则模板',       'F', NULL, NULL, 'tools:regex:template:delete', NULL, 4, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (608, 6081, 6082, 6083, 6084);

-- ========== 4) 全局共享正则模板表 ==========
CREATE TABLE IF NOT EXISTS sys_regex_template (
  id          BIGINT        NOT NULL COMMENT '雪花主键（种子数据用 19XX 固定号段）',
  name        VARCHAR(100)  NOT NULL COMMENT '模板名称（全局唯一）',
  pattern     VARCHAR(2000) NOT NULL COMMENT '正则表达式',
  flags       VARCHAR(10)   NOT NULL DEFAULT '' COMMENT '标志组合（imux 的子集，对应 Java Pattern 标志）',
  category    VARCHAR(50)   NOT NULL DEFAULT '通用' COMMENT '分类（校验/提取/替换/日志/其他）',
  description VARCHAR(500)           DEFAULT NULL COMMENT '用途说明',
  sample      VARCHAR(500)           DEFAULT NULL COMMENT '示例文本提示',
  sort        INT           NOT NULL DEFAULT 0 COMMENT '排序（小在前）',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_regex_template_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正则模板（全局共享）';

-- ========== 5) 种子数据（常用模板示例，幂等） ==========
INSERT INTO sys_regex_template (id, name, pattern, flags, category, description, sample, sort)
VALUES
  (1901, '中国大陆手机号',    '1[3-9]\\d{9}',                                                                                              '',  '校验', '11 位，1 开头第二位 3-9',                          '13812345678',              1),
  (1902, '邮箱地址',          '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}',                                                           'i', '校验', '通用邮箱格式',                                     'user@example.com',         2),
  (1903, 'IPv4 地址',         '(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)',                  '',  '校验', '严格校验每段 0-255',                               '192.168.1.1',              3),
  (1904, '18 位身份证号',     '[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:[0-2]\\d|3[01])\\d{3}[\\dXx]',                             '',  '校验', '仅格式校验，不含校验位算法',                       '11010519491231002X',       4),
  (1905, 'URL（http/https）', 'https?://[^\\s]+',                                                                                          'i', '提取', '简单 URL 提取',                                    'https://example.com/a?b=1', 5),
  (1906, '日期 yyyy-MM-dd',   '\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])',                                                         '',  '提取', 'ISO 风格日期',                                     '2026-09-24',               6),
  (1907, 'HTML 成对标签',     '<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>(.*?)</\\1>',                                                               's', '提取', '成对标签与内容（捕获组 1=标签名 2=内容）',         '<b>hello</b>',             7)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), pattern = VALUES(pattern), flags = VALUES(flags),
  category = VALUES(category), description = VALUES(description),
  sample = VALUES(sample), sort = VALUES(sort), updated_at = NOW();
