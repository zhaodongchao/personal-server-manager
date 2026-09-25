-- ============================================================================
-- V21  证件解析工具（日常工具 -> 610）+ 基础数据中心（系统管理 -> 209）
--
-- 一、菜单：
--   610   证件解析（C /tools/cert-parser） tools:cert:list
--   6101  解析（F）                        tools:cert:exec
--   209   基础数据（C /system/basedata）   system:basedata:list
--   2091  手动同步（F）                    system:basedata:sync
--   2092  数据维护（F）                    system:basedata:edit
--
-- 二、表：sys_region（4级行政区划）/ sys_phone_segment（手机号段）
--        / sys_bank_bin（银行卡BIN）/ sys_basedata_sync_log（同步日志）
--      区划 ~4.5 万行不进 Flyway 种子：首次部署后在「基础数据」页手动同步。
--
-- 三、sys_config 种子（数据源与开关）+ app_job 种子（每周日凌晨 4 点同步）。
-- 隐私红线：证件号解析不落库，本迁移不含任何证件号数据。
-- ============================================================================

-- ========== 1) 工具页菜单（610，父 600 日常工具） ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (610, 600, '证件解析', 'C', '/tools/cert-parser', '/tools/cert-parser/index', 'tools:cert:list', 'lucide:id-card', 10, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (6101, 610, '解析', 'F', NULL, NULL, 'tools:cert:exec', NULL, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 2) 管理页菜单（209，父 200 系统管理） ==========
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (209, 200, '基础数据', 'C', '/system/basedata', '/system/basedata/index', 'system:basedata:list', 'lucide:database', 9, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  route_path = VALUES(route_path), component = VALUES(component), perms = VALUES(perms),
  icon = VALUES(icon), sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status),
  updated_at = NOW();

INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, route_path, component, perms, icon, sort, visible, status, created_at, updated_at)
VALUES
  (2091, 209, '手动同步', 'F', NULL, NULL, 'system:basedata:sync', NULL, 1, 1, 1, NOW(), NOW()),
  (2092, 209, '数据维护', 'F', NULL, NULL, 'system:basedata:edit', NULL, 2, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
  perms = VALUES(perms), updated_at = NOW();

-- ========== 3) 授予超级管理员 ==========
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
 WHERE id IN (610, 6101, 209, 2091, 2092);

-- ========== 4) 行政区划（4 级，无种子，手动/定时同步） ==========
CREATE TABLE IF NOT EXISTS sys_region (
  id          BIGINT       NOT NULL COMMENT '雪花ID',
  code        VARCHAR(12)  NOT NULL COMMENT '区划代码：省2/市4/县6/乡9位（统计局口径）',
  name        VARCHAR(64)  NOT NULL COMMENT '名称（全称）',
  short_name  VARCHAR(32)  NULL     COMMENT '简称（如：内蒙古）',
  level       TINYINT      NOT NULL COMMENT '1省 2市 3区县 4乡镇街道（预留5村居）',
  parent_code VARCHAR(12)  NOT NULL COMMENT '父级代码（省级为空串）',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用（年度更新中被撤销的置0，保留可解析性）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_code (code),
  KEY idx_region_parent (parent_code),
  KEY idx_region_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行政区划（4级）';

-- ========== 5) 手机号段 ==========
CREATE TABLE IF NOT EXISTS sys_phone_segment (
  id          BIGINT      NOT NULL COMMENT '雪花ID（种子用固定号段 210XX）',
  prefix      VARCHAR(4)  NOT NULL COMMENT '号段前缀（3-4位）',
  operator    VARCHAR(32) NOT NULL COMMENT '运营商：移动/联通/电信/广电/虚拟运营商',
  seg_type    TINYINT     NOT NULL COMMENT '1基础运营商 2虚拟运营商 3物联卡',
  note        VARCHAR(128) NULL COMMENT '备注',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone_prefix (prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手机号段';

-- ========== 6) 银行卡 BIN ==========
CREATE TABLE IF NOT EXISTS sys_bank_bin (
  id         BIGINT       NOT NULL COMMENT '雪花ID（种子用固定号段 220XX）',
  bin        VARCHAR(10)  NOT NULL COMMENT 'BIN前缀（6-10位）',
  bank_name  VARCHAR(64)  NOT NULL COMMENT '发卡行全称（中国工商银行）',
  bank_short VARCHAR(32)  NULL     COMMENT '简称（工行）',
  card_type  TINYINT      NOT NULL COMMENT '1借记卡 2贷记卡(信用卡) 3准贷记卡',
  card_len   TINYINT      NOT NULL COMMENT '标准卡长（16/19）',
  note       VARCHAR(128) NULL COMMENT '备注',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_bin (bin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='银行卡BIN';

-- ========== 7) 基础数据同步日志 ==========
CREATE TABLE IF NOT EXISTS sys_basedata_sync_log (
  id            BIGINT      NOT NULL COMMENT '雪花ID',
  data_type     VARCHAR(16) NOT NULL COMMENT 'region/phone/bin',
  trigger_type  TINYINT     NOT NULL COMMENT '1手动 2定时',
  status        TINYINT     NOT NULL COMMENT '0进行中 1成功 2失败',
  rows_total    INT         NULL COMMENT '源数据总行数',
  rows_inserted INT         NULL COMMENT '新增行数',
  rows_updated  INT         NULL COMMENT '更新行数',
  rows_disabled INT         NULL COMMENT '软删（置0）行数',
  message       VARCHAR(512) NULL COMMENT '结果摘要/失败原因',
  started_at    DATETIME    NOT NULL COMMENT '开始时间',
  finished_at   DATETIME    NULL COMMENT '结束时间',
  PRIMARY KEY (id),
  KEY idx_bd_sync_type_time (data_type, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='基础数据同步日志（定时任务清理90天前）';

-- ========== 8) 手机号段种子（现行主流号段，幂等） ==========
INSERT INTO sys_phone_segment (id, prefix, operator, seg_type, note) VALUES
  -- 移动（基础）
  (21001, '134', '移动', 1, NULL), (21002, '135', '移动', 1, NULL), (21003, '136', '移动', 1, NULL),
  (21004, '137', '移动', 1, NULL), (21005, '138', '移动', 1, NULL), (21006, '139', '移动', 1, NULL),
  (21007, '147', '移动', 1, '数据卡'), (21008, '150', '移动', 1, NULL), (21009, '151', '移动', 1, NULL),
  (21010, '152', '移动', 1, NULL), (21011, '157', '移动', 1, NULL), (21012, '158', '移动', 1, NULL),
  (21013, '159', '移动', 1, NULL), (21014, '172', '移动', 1, NULL), (21015, '178', '移动', 1, NULL),
  (21016, '182', '移动', 1, NULL), (21017, '183', '移动', 1, NULL), (21018, '184', '移动', 1, NULL),
  (21019, '187', '移动', 1, NULL), (21020, '188', '移动', 1, NULL), (21021, '195', '移动', 1, NULL),
  (21022, '197', '移动', 1, NULL), (21023, '198', '移动', 1, NULL), (21024, '148', '移动', 3, '物联卡'),
  -- 联通（基础）
  (21031, '130', '联通', 1, NULL), (21032, '131', '联通', 1, NULL), (21033, '132', '联通', 1, NULL),
  (21034, '145', '联通', 1, '数据卡'), (21035, '146', '联通', 1, NULL), (21036, '155', '联通', 1, NULL),
  (21037, '156', '联通', 1, NULL), (21038, '166', '联通', 1, NULL), (21039, '175', '联通', 1, NULL),
  (21040, '176', '联通', 1, NULL), (21041, '185', '联通', 1, NULL), (21042, '186', '联通', 1, NULL),
  (21043, '196', '联通', 1, NULL),
  -- 电信（基础）
  (21051, '133', '电信', 1, NULL), (21052, '149', '电信', 1, '数据卡'), (21053, '153', '电信', 1, NULL),
  (21054, '162', '电信', 1, NULL), (21055, '173', '电信', 1, NULL), (21056, '174', '电信', 1, '卫星通信号段'),
  (21057, '177', '电信', 1, NULL), (21058, '180', '电信', 1, NULL), (21059, '181', '电信', 1, NULL),
  (21060, '189', '电信', 1, NULL), (21061, '190', '电信', 1, NULL), (21062, '191', '电信', 1, NULL),
  (21063, '193', '电信', 1, NULL), (21064, '199', '电信', 1, NULL),
  -- 广电
  (21071, '192', '广电', 1, NULL),
  -- 虚拟运营商
  (21081, '165', '虚拟运营商', 2, '联通转售'), (21082, '167', '虚拟运营商', 2, '联通转售'),
  (21083, '170', '虚拟运营商', 2, NULL),       (21084, '171', '虚拟运营商', 2, NULL)
ON DUPLICATE KEY UPDATE
  operator = VALUES(operator), seg_type = VALUES(seg_type), note = VALUES(note), updated_at = NOW();

-- ========== 9) 银行卡 BIN 种子（主流发卡行代表性前缀，可在管理页维护） ==========
INSERT INTO sys_bank_bin (id, bin, bank_name, bank_short, card_type, card_len, note) VALUES
  -- 中国工商银行
  (22001, '622200', '中国工商银行', '工行', 1, 19, NULL),
  (22002, '622202', '中国工商银行', '工行', 1, 19, NULL),
  (22003, '621226', '中国工商银行', '工行', 1, 19, NULL),
  (22004, '427020', '中国工商银行', '工行', 2, 16, '牡丹信用卡（Visa）'),
  (22005, '548943', '中国工商银行', '工行', 2, 16, '牡丹信用卡（MasterCard）'),
  -- 中国建设银行
  (22011, '621700', '中国建设银行', '建行', 1, 19, NULL),
  (22012, '622700', '中国建设银行', '建行', 1, 19, NULL),
  (22013, '436742', '中国建设银行', '建行', 2, 16, '龙卡信用卡（Visa）'),
  -- 中国农业银行
  (22021, '622848', '中国农业银行', '农行', 1, 19, NULL),
  -- 中国银行
  (22031, '621785', '中国银行',     '中行', 1, 19, NULL),
  (22032, '621786', '中国银行',     '中行', 1, 19, NULL),
  (22033, '456351', '中国银行',     '中行', 2, 16, '长城信用卡（Visa）'),
  -- 交通银行
  (22041, '622262', '交通银行',     '交行', 1, 19, NULL),
  -- 中国邮政储蓄银行
  (22051, '622188', '中国邮政储蓄银行', '邮储', 1, 19, NULL),
  (22052, '621799', '中国邮政储蓄银行', '邮储', 1, 19, NULL),
  -- 招商银行
  (22061, '622588', '招商银行', '招行', 1, 16, NULL),
  (22062, '439188', '招商银行', '招行', 2, 16, '信用卡（Visa）'),
  -- 中信银行
  (22071, '622690', '中信银行', '中信', 1, 16, NULL),
  (22072, '622688', '中信银行', '中信', 2, 16, '信用卡'),
  -- 浦发银行
  (22081, '622516', '上海浦东发展银行', '浦发', 1, 16, NULL),
  -- 光大银行
  (22091, '622660', '中国光大银行', '光大', 2, 16, '阳光信用卡'),
  -- 民生银行
  (22101, '622617', '中国民生银行', '民生', 1, 19, NULL),
  -- 广发银行
  (22111, '622715', '广发银行', '广发', 2, 16, '信用卡'),
  -- 兴业银行
  (22121, '622909', '兴业银行', '兴业', 1, 19, NULL)
ON DUPLICATE KEY UPDATE
  bank_name = VALUES(bank_name), bank_short = VALUES(bank_short),
  card_type = VALUES(card_type), card_len = VALUES(card_len),
  note = VALUES(note), updated_at = NOW();

-- ========== 10) sys_config 种子（数据源与开关，内置不可删） ==========
INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, remark) VALUES
  (2101, '区划数据源地址模板',
   'tools.basedata.region.source-url',
   'https://gh-proxy.com/raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/{level}.json',
   'Y', '行政区划同步源，{level} 依次替换为 provinces/cities/areas/streets；源自国家统计局 2023 版'),
  (2102, '号段数据源地址',
   'tools.basedata.phone.source-url', '', 'Y',
   '可选外部号段源（JSON 数组 code/prefix + operator）；留空=仅用内置种子'),
  (2103, 'BIN数据源地址',
   'tools.basedata.bin.source-url', '', 'Y',
   '可选外部 BIN 源（JSON 数组 code/bin + bankName）；留空=仅用内置种子'),
  (2104, '同步日志保留天数',
   'tools.basedata.sync.log-retention-days', '90', 'Y',
   '定时同步时顺带清理该天数前的同步日志')
ON DUPLICATE KEY UPDATE
  config_name = VALUES(config_name), config_value = VALUES(config_value),
  config_type = VALUES(config_type), remark = VALUES(remark);

-- ========== 11) 调度中心种子任务（每周日凌晨 4 点全量同步） ==========
-- 走既有 INTERNAL 处理器 + 内置任务 SPI（TOOLS_BASEDATA_SYNC），不新增 handler 分支。
INSERT INTO app_job
  (id, job_name, job_desc, executor_id, handler, handler_param, cron_expr,
   route_strategy, block_strategy, timeout_sec, retry_count, status, created_at, updated_at)
VALUES
  (2100, 'basedata-weekly-sync', '基础数据周同步（行政区划/号段/BIN）', 1, 'INTERNAL',
   JSON_OBJECT('task', 'TOOLS_BASEDATA_SYNC', 'params', JSON_OBJECT()),
   '0 0 4 ? * SUN', 'FIRST', 'DISCARD_LATER', 900, 0, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  job_desc = VALUES(job_desc), executor_id = VALUES(executor_id), handler = VALUES(handler),
  handler_param = VALUES(handler_param), cron_expr = VALUES(cron_expr),
  block_strategy = VALUES(block_strategy), timeout_sec = VALUES(timeout_sec),
  retry_count = VALUES(retry_count), updated_at = NOW();
