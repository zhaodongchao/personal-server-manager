-- =============================================================================
-- ServerPanel V1__init.sql
-- 16 张表 DDL + 种子数据（MySQL 8.x，utf8mb4）
-- 约定：InnoDB；主键 BIGINT（雪花ID 由应用侧生成，种子数据用固定小 ID 便于阅读）
-- =============================================================================

-- ========== 系统管理 ==========
CREATE TABLE sys_user (
  id            BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  username      VARCHAR(30)  NOT NULL COMMENT '登录名',
  nickname      VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '昵称',
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  email         VARCHAR(100)          DEFAULT NULL,
  phone         VARCHAR(20)           DEFAULT NULL,
  avatar        VARCHAR(255)          DEFAULT NULL,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  last_login_at DATETIME               DEFAULT NULL,
  last_login_ip VARCHAR(50)            DEFAULT NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE sys_role (
  id         BIGINT      NOT NULL,
  role_name  VARCHAR(30) NOT NULL COMMENT '角色名',
  role_key   VARCHAR(60) NOT NULL COMMENT '权限字符',
  sort       INT         NOT NULL DEFAULT 0,
  status     TINYINT     NOT NULL DEFAULT 1,
  remark     VARCHAR(255)         DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_key (role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-角色';

CREATE TABLE sys_menu (
  id         BIGINT       NOT NULL,
  parent_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID,0为根',
  menu_name  VARCHAR(30)  NOT NULL,
  menu_type  CHAR(1)      NOT NULL COMMENT 'M目录 C菜单 F按钮',
  route_path VARCHAR(200)          DEFAULT NULL COMMENT '路由地址',
  component  VARCHAR(255)          DEFAULT NULL COMMENT '前端组件路径(Vben后端路由格式)',
  perms      VARCHAR(100)          DEFAULT NULL COMMENT '权限标识 如 system:user:add',
  icon       VARCHAR(60)           DEFAULT NULL,
  sort       INT          NOT NULL DEFAULT 0,
  visible    TINYINT      NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
  status     TINYINT      NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限';

CREATE TABLE sys_role_menu (
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单';

CREATE TABLE sys_dict_type (
  id         BIGINT      NOT NULL,
  dict_name  VARCHAR(60) NOT NULL,
  dict_type  VARCHAR(60) NOT NULL,
  status     TINYINT     NOT NULL DEFAULT 1,
  remark     VARCHAR(255)         DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典类型';

CREATE TABLE sys_dict_data (
  id         BIGINT       NOT NULL,
  dict_type  VARCHAR(60)  NOT NULL,
  dict_label VARCHAR(100) NOT NULL,
  dict_value VARCHAR(100) NOT NULL,
  sort       INT          NOT NULL DEFAULT 0,
  status     TINYINT      NOT NULL DEFAULT 1,
  is_default TINYINT      NOT NULL DEFAULT 0,
  remark     VARCHAR(255)          DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典数据';

CREATE TABLE sys_config (
  id           BIGINT       NOT NULL,
  config_name  VARCHAR(100) NOT NULL,
  config_key   VARCHAR(100) NOT NULL,
  config_value VARCHAR(500) NOT NULL DEFAULT '',
  config_type  CHAR(1)      NOT NULL DEFAULT 'N' COMMENT 'Y内置不可删 N用户',
  remark       VARCHAR(255)          DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参数配置';

CREATE TABLE sys_audit_log (
  id             BIGINT       NOT NULL,
  operator       VARCHAR(30)  NOT NULL COMMENT '操作人',
  module         VARCHAR(30)  NOT NULL COMMENT 'system/file/ops/appstack',
  action         VARCHAR(60)  NOT NULL COMMENT '动作 如 user:add',
  method         VARCHAR(200) NOT NULL COMMENT '类#方法',
  request_uri    VARCHAR(255) NOT NULL,
  request_method VARCHAR(10)  NOT NULL,
  params         TEXT COMMENT '入参JSON(截断2KB)',
  result_code    INT          NOT NULL COMMENT '响应code',
  error_msg      VARCHAR(500)          DEFAULT NULL,
  duration_ms    BIGINT       NOT NULL,
  ip             VARCHAR(50)  NOT NULL,
  user_agent     VARCHAR(255)          DEFAULT NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_operator (operator),
  KEY idx_created_at (created_at),
  KEY idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志(只增不改)';

CREATE TABLE sys_login_log (
  id         BIGINT      NOT NULL,
  username   VARCHAR(30) NOT NULL,
  ip         VARCHAR(50) NOT NULL,
  status     TINYINT     NOT NULL COMMENT '1成功 0失败',
  message    VARCHAR(200)         DEFAULT NULL,
  user_agent VARCHAR(255)         DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_username (username),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志';

-- ========== 监控 ==========
CREATE TABLE mon_metric_hour (
  id          BIGINT        NOT NULL,
  metric_time DATETIME      NOT NULL COMMENT '统计小时(整点)',
  cpu_usage   DECIMAL(5,2)  NOT NULL COMMENT '平均CPU%',
  mem_usage   DECIMAL(5,2)  NOT NULL,
  disk_usage  DECIMAL(5,2)  NOT NULL COMMENT '根分区使用%',
  net_in_mb   DECIMAL(12,2) NOT NULL COMMENT '小时流入MB',
  net_out_mb  DECIMAL(12,2) NOT NULL,
  load_avg    DECIMAL(6,2)  NOT NULL COMMENT '1分钟负载均值',
  PRIMARY KEY (id),
  UNIQUE KEY uk_metric_time (metric_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='监控小时聚合(实时数据在Redis)';

-- ========== 运维工具 ==========
CREATE TABLE ops_cron_job (
  id          BIGINT       NOT NULL,
  name        VARCHAR(60)  NOT NULL COMMENT '任务名',
  cron_expr   VARCHAR(60)  NOT NULL COMMENT '5段cron表达式',
  command     VARCHAR(500) NOT NULL COMMENT '命令(经白名单校验)',
  timeout_sec INT          NOT NULL DEFAULT 300,
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark      VARCHAR(255)          DEFAULT NULL,
  last_run_at DATETIME               DEFAULT NULL,
  next_run_at DATETIME               DEFAULT NULL COMMENT '调度器计算的下次执行时间',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_next (status, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划任务';

CREATE TABLE ops_cron_log (
  id          BIGINT      NOT NULL,
  job_id      BIGINT      NOT NULL,
  job_name    VARCHAR(60) NOT NULL COMMENT '冗余,防任务删除后丢失信息',
  exit_code   INT         NOT NULL,
  output      TEXT COMMENT '输出(截断64KB)',
  started_at  DATETIME    NOT NULL,
  finished_at DATETIME             DEFAULT NULL,
  duration_ms BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_job_id_started (job_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划任务执行日志';

CREATE TABLE file_recycle_bin (
  id          BIGINT       NOT NULL,
  origin_path VARCHAR(500) NOT NULL COMMENT '删除前原路径',
  trash_path  VARCHAR(500) NOT NULL COMMENT '回收站内路径',
  file_name   VARCHAR(255) NOT NULL,
  is_dir      TINYINT     NOT NULL DEFAULT 0,
  size        BIGINT      NOT NULL DEFAULT 0 COMMENT '字节',
  operator    VARCHAR(30) NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '删除时间',
  expire_at   DATETIME    NOT NULL COMMENT '过期时间(默认+7天,由定时清理)',
  PRIMARY KEY (id),
  KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件回收站';

-- ========== 应用栈 ==========
CREATE TABLE app_website (
  id          BIGINT       NOT NULL,
  domain      VARCHAR(255) NOT NULL COMMENT '主域名',
  site_name   VARCHAR(60)  NOT NULL,
  site_type   VARCHAR(20)  NOT NULL DEFAULT 'proxy' COMMENT 'proxy反代 / static静态',
  upstream    VARCHAR(500)          DEFAULT NULL COMMENT '反代目标 http://127.0.0.1:3000',
  static_root VARCHAR(255)          DEFAULT NULL COMMENT '静态根目录(须在文件白名单内)',
  ssl_enabled TINYINT      NOT NULL DEFAULT 0,
  cert_path   VARCHAR(255)          DEFAULT NULL,
  key_path    VARCHAR(255)          DEFAULT NULL,
  conf_path   VARCHAR(255) NOT NULL COMMENT '/etc/nginx/panel.d/{domain}.conf',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1运行 0停用',
  remark      VARCHAR(255)          DEFAULT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_domain (domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Nginx站点';

CREATE TABLE app_database (
  id         BIGINT      NOT NULL,
  db_name    VARCHAR(64) NOT NULL,
  db_user    VARCHAR(64) NOT NULL COMMENT '该库的授权账号(密码不入库)',
  charset    VARCHAR(20) NOT NULL DEFAULT 'utf8mb4',
  remark     VARCHAR(255)         DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_db_name (db_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='面板代管MySQL库';

-- =============================================================================
-- 种子数据
-- =============================================================================

-- 管理员（密码 BCrypt(Admin@123)，构建时生成真值替换占位符）
INSERT INTO sys_user (id, username, nickname, password, status) VALUES
(1, 'admin', '超级管理员', '$2a$10$JMcZSP5XvSXoU/WWg.qEduTuIhR3ZVgICOm0SxEvhrEmhSYwHPRsu', 1);

INSERT INTO sys_role (id, role_name, role_key, sort, status, remark) VALUES
(1, '超级管理员', 'admin', 0, 1, '拥有全部权限');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 菜单（与前端页面清单一一对应）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status) VALUES
-- 工作台
(100, 0,   '工作台', 'C', '/dashboard', '/dashboard/index', 'dashboard:view', 'lucide:activity', 0, 1, 1),
-- 系统管理
(200, 0,   '系统管理', 'M', '/system', NULL, NULL, 'lucide:settings', 1, 1, 1),
(201, 200, '用户管理', 'C', '/system/user', '/system/user/index', 'system:user:list', NULL, 1, 1, 1),
(2011, 201, '用户新增', 'F', NULL, NULL, 'system:user:add', NULL, 1, 1, 1),
(2012, 201, '用户编辑', 'F', NULL, NULL, 'system:user:edit', NULL, 2, 1, 1),
(2013, 201, '用户删除', 'F', NULL, NULL, 'system:user:delete', NULL, 3, 1, 1),
(2014, 201, '重置密码', 'F', NULL, NULL, 'system:user:reset-password', NULL, 4, 1, 1),
(202, 200, '角色管理', 'C', '/system/role', '/system/role/index', 'system:role:list', NULL, 2, 1, 1),
(2021, 202, '角色新增', 'F', NULL, NULL, 'system:role:add', NULL, 1, 1, 1),
(2022, 202, '角色编辑', 'F', NULL, NULL, 'system:role:edit', NULL, 2, 1, 1),
(2023, 202, '角色删除', 'F', NULL, NULL, 'system:role:delete', NULL, 3, 1, 1),
(203, 200, '菜单管理', 'C', '/system/menu', '/system/menu/index', 'system:menu:list', NULL, 3, 1, 1),
(2031, 203, '菜单新增', 'F', NULL, NULL, 'system:menu:add', NULL, 1, 1, 1),
(2032, 203, '菜单编辑', 'F', NULL, NULL, 'system:menu:edit', NULL, 2, 1, 1),
(2033, 203, '菜单删除', 'F', NULL, NULL, 'system:menu:delete', NULL, 3, 1, 1),
(204, 200, '字典管理', 'C', '/system/dict', '/system/dict/index', 'system:dict:list', NULL, 4, 1, 1),
(2041, 204, '字典新增', 'F', NULL, NULL, 'system:dict:add', NULL, 1, 1, 1),
(2042, 204, '字典编辑', 'F', NULL, NULL, 'system:dict:edit', NULL, 2, 1, 1),
(2043, 204, '字典删除', 'F', NULL, NULL, 'system:dict:delete', NULL, 3, 1, 1),
(205, 200, '参数配置', 'C', '/system/config', '/system/config/index', 'system:config:list', NULL, 5, 1, 1),
(2051, 205, '参数编辑', 'F', NULL, NULL, 'system:config:edit', NULL, 1, 1, 1),
(2052, 205, '参数删除', 'F', NULL, NULL, 'system:config:delete', NULL, 2, 1, 1),
(206, 200, '审计日志', 'C', '/system/audit-log', '/system/audit-log/index', 'system:audit:list', NULL, 6, 1, 1),
(207, 200, '登录日志', 'C', '/system/login-log', '/system/login-log/index', 'system:loginlog:list', NULL, 7, 1, 1),
-- 文件管理
(300, 0,   '文件管理', 'M', '/file', NULL, NULL, 'lucide:folder', 2, 1, 1),
(301, 300, '文件管理', 'C', '/file/index', '/file/index', 'file:list', NULL, 1, 1, 1),
(3011, 301, '新建文件夹', 'F', NULL, NULL, 'file:mkdir', NULL, 1, 1, 1),
(3012, 301, '上传', 'F', NULL, NULL, 'file:upload', NULL, 2, 1, 1),
(3013, 301, '编辑', 'F', NULL, NULL, 'file:edit', NULL, 3, 1, 1),
(3014, 301, '删除', 'F', NULL, NULL, 'file:delete', NULL, 4, 1, 1),
(3015, 301, '修改权限', 'F', NULL, NULL, 'file:perm', NULL, 5, 1, 1),
(3016, 301, '压缩解压', 'F', NULL, NULL, 'file:compress', NULL, 6, 1, 1),
-- 运维工具
(400, 0,   '运维工具', 'M', '/ops', NULL, NULL, 'lucide:wrench', 3, 1, 1),
(401, 400, '进程管理', 'C', '/ops/process', '/ops/process/index', 'ops:process:list', NULL, 1, 1, 1),
(4011, 401, '终止进程', 'F', NULL, NULL, 'ops:process:kill', NULL, 1, 1, 1),
(402, 400, '服务管理', 'C', '/ops/service', '/ops/service/index', 'ops:service:list', NULL, 2, 1, 1),
(4021, 402, '服务操作', 'F', NULL, NULL, 'ops:service:manage', NULL, 1, 1, 1),
(403, 400, '计划任务', 'C', '/ops/cron', '/ops/cron/index', 'ops:cron:list', NULL, 3, 1, 1),
(4031, 403, '任务新增', 'F', NULL, NULL, 'ops:cron:add', NULL, 1, 1, 1),
(4032, 403, '任务编辑', 'F', NULL, NULL, 'ops:cron:edit', NULL, 2, 1, 1),
(4033, 403, '任务删除', 'F', NULL, NULL, 'ops:cron:delete', NULL, 3, 1, 1),
(4034, 403, '立即执行', 'F', NULL, NULL, 'ops:cron:run', NULL, 4, 1, 1),
(404, 400, '防火墙', 'C', '/ops/firewall', '/ops/firewall/index', 'ops:firewall:list', NULL, 4, 1, 1),
(4041, 404, '防火墙写操作', 'F', NULL, NULL, 'ops:firewall:write', NULL, 1, 1, 1),
-- 应用栈
(500, 0,   '应用栈', 'M', '/appstack', NULL, NULL, 'lucide:layers', 4, 1, 1),
(501, 500, 'Docker', 'C', '/appstack/docker', '/appstack/docker/index', 'appstack:docker:list', NULL, 1, 1, 1),
(5011, 501, '容器操作', 'F', NULL, NULL, 'appstack:docker:manage', NULL, 1, 1, 1),
(502, 500, '网站管理', 'C', '/appstack/website', '/appstack/website/index', 'appstack:website:list', NULL, 2, 1, 1),
(5021, 502, '网站新增', 'F', NULL, NULL, 'appstack:website:add', NULL, 1, 1, 1),
(5022, 502, '网站编辑', 'F', NULL, NULL, 'appstack:website:edit', NULL, 2, 1, 1),
(5023, 502, '网站删除', 'F', NULL, NULL, 'appstack:website:delete', NULL, 3, 1, 1),
(503, 500, '数据库', 'C', '/appstack/database', '/appstack/database/index', 'appstack:database:list', NULL, 3, 1, 1),
(5031, 503, '建库', 'F', NULL, NULL, 'appstack:database:add', NULL, 1, 1, 1),
(5032, 503, '删库', 'F', NULL, NULL, 'appstack:database:delete', NULL, 2, 1, 1),
(5033, 503, '备份恢复', 'F', NULL, NULL, 'appstack:database:backup', NULL, 3, 1, 1);

-- 超级管理员拥有全部菜单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

-- 参数配置种子
INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, remark) VALUES
(1, '监控采集间隔(秒)', 'monitor.interval-seconds', '5', 'Y', 'OSHI 采集与 WS 推送频率'),
(2, '文件根目录白名单', 'file.roots', '/www,/srv,/var/www', 'Y', '逗号分隔，文件管理仅允许访问这些目录'),
(3, '回收站保留天数', 'file.trash.retain-days', '7', 'Y', '过期自动清理'),
(4, '登录失败上限', 'login.max-fail', '5', 'Y', '连续失败次数'),
(5, '登录锁定时长(分钟)', 'login.lock-minutes', '15', 'Y', '超过失败上限后的锁定时长');

-- 字典种子
INSERT INTO sys_dict_type (id, dict_name, dict_type, status, remark) VALUES
(1, '系统开关', 'sys_normal_disable', 1, '通用启用/停用'),
(2, '是否', 'sys_yes_no', 1, '通用是/否');

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort, status, is_default, remark) VALUES
(1, 'sys_normal_disable', '启用', '1', 1, 1, 1, NULL),
(2, 'sys_normal_disable', '停用', '0', 2, 1, 0, NULL),
(3, 'sys_yes_no', '是', 'Y', 1, 1, 1, NULL),
(4, 'sys_yes_no', '否', 'N', 2, 1, 0, NULL);
