-- =============================================================================
-- ServerPanel V4__quick_nav_and_overview.sql
-- 1) 菜单重构：原根级"工作台"改为"概览"目录，下设"服务器监控"(默认页)与"工作台"
-- 2) 新增 sys_quick_nav 快捷导航配置表（工作台快捷导航的数据源）
-- 3) 系统管理下新增"快捷导航管理"菜单（增删改查）
-- =============================================================================

-- ---------- 菜单重构 ----------
-- 原"工作台"(id=100) 改为"概览"目录
UPDATE sys_menu
SET menu_name = '概览',
    menu_type = 'M',
    route_path = '/overview',
    component  = NULL,
    perms      = NULL,
    icon       = 'lucide:layout-dashboard'
WHERE id = 100;

-- 原"服务器监控"(id=405) 移入"概览"目录，作为第一个菜单（默认页）
UPDATE sys_menu
SET parent_id  = 100,
    menu_name  = '服务器监控',
    route_path = '/overview/monitor',
    sort       = 1
WHERE id = 405;

-- "工作台"作为"概览"下第二个菜单
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status)
VALUES (102, 100, '工作台', 'C', '/overview/workspace', '/dashboard/index', 'dashboard:view', 'lucide:activity', 2, 1, 1);

-- ---------- 快捷导航管理菜单 ----------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status) VALUES
(208, 200, '快捷导航管理', 'C', '/system/quick-nav', '/system/quick-nav/index', 'system:quick-nav:list', 'lucide:nav', 8, 1, 1),
(2081, 208, '快捷导航新增', 'F', NULL, NULL, 'system:quick-nav:add', NULL, 1, 1, 1),
(2082, 208, '快捷导航编辑', 'F', NULL, NULL, 'system:quick-nav:edit', NULL, 2, 1, 1),
(2083, 208, '快捷导航删除', 'F', NULL, NULL, 'system:quick-nav:delete', NULL, 3, 1, 1);

-- 超级管理员授权（历史库需单独补授权）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 102),
(1, 208),
(1, 2081),
(1, 2082),
(1, 2083);

-- ---------- 快捷导航配置表 ----------
CREATE TABLE sys_quick_nav (
  id           BIGINT       NOT NULL,
  display_name VARCHAR(50)  NOT NULL COMMENT '展示名',
  port         INT          NOT NULL DEFAULT -1 COMMENT 'Web 端口（-1 未知）',
  path         VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'Web 路径前缀',
  icon         VARCHAR(60)  NOT NULL DEFAULT 'lucide:app-window' COMMENT '前端图标',
  sort         INT          NOT NULL DEFAULT 0 COMMENT '排序（小在前）',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark       VARCHAR(255)          DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快捷导航配置';

-- 种子数据（常见自建服务）
INSERT INTO sys_quick_nav (id, display_name, port, path, icon, sort, status) VALUES
(1,  'GitLab',       80,    '', 'lucide:gitlab',        1,  1),
(2,  'Jenkins',      8080,  '', 'lucide:hammer',        2,  1),
(3,  'Jellyfin',     8096,  '', 'lucide:tv',            3,  1),
(4,  'Nginx',        80,    '', 'lucide:server',        4,  1),
(5,  'Apache',       80,    '', 'lucide:globe',         5,  1),
(6,  'Portainer',    9000,  '', 'lucide:container',     6,  1),
(7,  'MinIO',        9001,  '', 'lucide:database',      7,  1),
(8,  'Nextcloud',    80,    '', 'lucide:cloud',         8,  1),
(9,  'Emby',         8096,  '', 'lucide:play',          9,  1),
(10, 'Plex',         32400, '', 'lucide:monitor-play', 10,  1),
(11, 'qBittorrent',  8080,  '', 'lucide:download',     11,  1),
(12, 'Sonarr',       8989,  '', 'lucide:film',         12,  1),
(13, 'Radarr',       7878,  '', 'lucide:film',         13,  1),
(14, 'Prowlarr',     9696,  '', 'lucide:search',       14,  1),
(15, 'Transmission', 9091,  '', 'lucide:download',     15,  1);
