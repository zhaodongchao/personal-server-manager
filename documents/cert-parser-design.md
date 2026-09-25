# 证件解析工具 + 基础数据中心 — 详细设计

> 模块：日常工具「证件解析」（菜单 610）+ 系统管理「基础数据」（菜单 209）
> 设计时间：2026-09-25　作者：zhaodc
> 状态：待评审
> 关联：api-spec.md（总契约）、V11__job_scheduler_module.sql（调度中心）、V19__regex_tool_and_template.sql（工具页模式参考）

---

## 一、需求与决策记录

| 决策点 | 结论 |
| --- | --- |
| 证件类型范围 | **7 种全量**：身份证 / 手机号 / 银行卡 / 统一社会信用代码 / 车牌号 / 回乡证 / 台胞证 |
| 行政区划深度 | **4 级**（省 / 市 / 区县 / 乡镇街道，约 4.5 万行），`level` 字段预留第 5 级扩展 |
| 管理界面位置 | 系统管理下新增「基础数据」页面（菜单 209），含行政区划 / 手机号段 / 银行卡 BIN 三个 Tab |
| 定时更新机制 | **复用 app_job 调度中心**：注册内置 handler + 种子任务（每周日凌晨 4 点），支持前端改 cron / 看日志 / 手动触发 |
| 隐私红线 | 证件号属敏感数据：解析**不落库**、审计 **recordParams=false**，接口日志不记录原文 |

---

## 二、七种证件构造规则（解析算法核心）

### 2.1 身份证（GB 11643-1999）

**18 位结构**：

```
┌─ 地址码 6 ─┬─ 出生日期码 8 ─┬─ 顺序码 3 ─┬ 校验 1 ┐
 110105     19491231         001          X
```

- **地址码（1-6 位）**：GB/T 2260 行政区划代码。首位大区：1 华北、2 东北、3 华东、4 中南、5 西南、6 西北、7 台湾、81 香港、82 澳门。前 2 位省、3-4 位市、5-6 位区县，与 `sys_region`（`code` 前缀匹配）联查出「省-市-区县」全称
- **出生日期码（7-14 位）**：YYYYMMDD，校验真实日历日期（含闰年 0229）
- **顺序码（15-17 位）**：同一地址码同日出生人员的顺序编号；**奇数 = 男，偶数 = 女**
- **校验码（第 18 位）**：ISO 7064:1983 MOD 11-2
  - 权重 `Wi = 2^(18-i) mod 11`，即 `[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]`（i=1..17）
  - `S = Σ ai×Wi`，`Y = S mod 11`
  - 校验码映射：`Y: 0 1 2 3 4 5 6 7 8 9 10 → '1','0','X','9','8','7','6','5','4','3','2'`
- **15 位老身份证**：6 地址 + 6 位出生（YYMMDD，升位补 19）+ 3 顺序，无校验码。解析时自动**升位为 18 位**并展示升位结果
- **扩展输出**：年龄（周岁）、生肖、星座、出生距今天数；地址码在 `sys_region` 命中失败时降级为「仅识别省级（按首位/前 2 位）」并提示区划数据未同步
- **容错提示**：输入含空格/X 大小写自动归一化；15 位升位提示「按 19xx 年补全」

### 2.2 手机号（11 位）

```
┌ 网号 1 ─┬ 识别位 1 ─┬ HLR 4 ─┬ 用户号 5 ┐
   1          3~9
```

- 结构规则：`1[3-9]\d{9}`，共 11 位
- **运营商归属**：按 3-4 位前缀查 `sys_phone_segment` 表（数据可管理、可同步），支持基础运营商（移动/联通/电信/广电）与虚拟运营商标注
- 输出：运营商、号段前缀、卡类型（基础/虚商/物联）、号段说明；表中无记录时输出「未收录号段（可能为新号段或输入有误）」
- **不做项**：手机号归属地（需号段-城市级映射大数据，数据量与准确性都不合适，明确列为不做）

### 2.3 银行卡

```
┌── BIN 卡识别码 6~10 ──┬────── 发卡行自定义 ──────┐
   6222 02                ...
```

- **Luhn 校验**（模 10，GB/T 15694 兼容）：从右往左，偶数位 ×2、超过 9 则减 9，全部求和后 `mod 10 == 0` 为合法
- **BIN 解析**：前 6-10 位最长前缀匹配 `sys_bank_bin` → 发卡行全称、卡种（借记/贷记/准贷记）、标准卡长（16/19）
- 输出：发卡行、卡种、卡长校验（实际位数 vs 标准位数）、Luhn 校验结论
- **不做项**：卡级别（白金/金卡等）——公开数据不准，不承诺

### 2.4 统一社会信用代码（GB 32100-2015）

```
┌ 部门 1 ┬ 类别 1 ┬ 登记机关区划 6 ┬ 主体标识码 9 ┬ 校验 1 ┐
   9        1        110105          xxxxxxxxx       X
```

- **字符集**：`0-9` + `ABCDEFGHJKLMNPQRTUWXY`（无 I、O、S、V、Z，防混淆）
- **第 1 位登记管理部门**：1 机构编制、2 外交、3 司法行政、4 文化、5 民政、6 旅游、7 宗教、8 工会、9 工商（市场主体）、N 农业、Y 其他
- **第 3-8 位**：登记管理机关行政区划码，联动 `sys_region`
- **校验码（第 18 位）**：MOD 31 算法
  - 字符值 `v`：数字 = 面值；字母 = 序位 + 10（A=10 … Y=30）
  - 权重 `Wi = 3^(i-1) mod 31`（i=1..17）
  - `c18 = 31 - (Σ vi×Wi mod 31)`，结果 31 取 0
- 输出：登记部门中文名、登记机关区划、主体标识码、校验结论

### 2.5 车牌号（GA 36-2018）

```
普通车牌（5 位序号）：  京 A · 12345      序号字符集：0-9 A-Z（无 I O）
新能源车牌（6 位序号）：京 AD · 12345    D=纯电动 F=插电式混动
                                        小型车：序号首位 D/F；大型车：序号末位 D/F
使领馆牌照：            使 123456 / 京 A·1234·领
```

- **第 1 位省份简称**：京 沪 津 渝 冀 晋 蒙 辽 吉 黑 苏 浙 皖 闽 赣 鲁 豫 鄂 湘 粤 桂 琼 川 贵 云 藏 陕 甘 青 宁 新（内置静态枚举，含大区归属）
- **第 2 位发牌机关字母**：A=省会，O=警用相关（说明性文案），按省份给出惯例说明（**不做**城市级反查——公开对照表不全，仅说明）
- 校验：结构正则 + 序号字符集校验（拒 I/O）+ 新能源位规则
- 输出：省份（含大区）、车牌类型（普通蓝牌 / 新能源 / 使领馆）、结构分段说明

### 2.6 港澳居民来往内地通行证（回乡证）

- 首位字母 **H = 香港 / M = 澳门** + 数字号码串（8~10 位，末 2 位为换发次数）
- **限制声明**：官方未公开校验算法，本工具做**结构级解析**（签发地、位数、号码段拆解），不做强校验
- 输出：签发地、号码主体、换发次数位拆解

### 2.7 台湾居民来往大陆通行证（台胞证）

- **旧版（纸本）**：8 位号码 + 2 位签注次数，共 10 位
- **台湾居民居住证（2018+）**：18 位，地址码 **830000**（台湾省）+ 出生日期 8 + 顺序码 3 + 校验码 1，**校验算法与身份证一致**（GB 11643 同款 MOD 11-2）
- 输出：证件版本判定（台胞证 / 居住证）、结构分段；居住证做完整校验码校验

---

## 三、数据库设计（Flyway V21__cert_parser_and_basedata.sql）

### 3.1 sys_region（行政区划，4 级，~4.5 万行）

```sql
CREATE TABLE sys_region (
  id          BIGINT       NOT NULL COMMENT '雪花ID',
  code        VARCHAR(12)  NOT NULL COMMENT '区划代码：省2/市4/县6/乡9位（统计局口径）',
  name        VARCHAR(64)  NOT NULL COMMENT '名称（全称）',
  short_name  VARCHAR(32)  NULL     COMMENT '简称（如：内蒙古）',
  level       TINYINT      NOT NULL COMMENT '1省 2市 3区县 4乡镇街道（预留5村居）',
  parent_code VARCHAR(12)  NOT NULL COMMENT '父级代码（省级为空串）',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用（年度更新中被撤销的置0，保留可解析性）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_parent (parent_code),
  KEY idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行政区划（4级）';
```

> **不放入 Flyway 种子**（4.5 万行塞迁移脚本不合理）：首次部署后在「基础数据」页手动触发一次同步；未同步时身份证解析自动降级（仅识别省级）。

### 3.2 sys_phone_segment（手机号段）

```sql
CREATE TABLE sys_phone_segment (
  id          BIGINT      NOT NULL,
  prefix      VARCHAR(4)  NOT NULL COMMENT '号段前缀（3-4位）',
  operator    VARCHAR(32) NOT NULL COMMENT '运营商：移动/联通/电信/广电',
  seg_type    TINYINT     NOT NULL COMMENT '1基础运营商 2虚拟运营商 3物联卡',
  note        VARCHAR(128) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_prefix (prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机号段';
-- Flyway 内置主流号段种子约 70 条（13x/14x/15x/16x/17x/18x/19x 现行段）
```

### 3.3 sys_bank_bin（银行卡 BIN）

```sql
CREATE TABLE sys_bank_bin (
  id         BIGINT       NOT NULL,
  bin        VARCHAR(10)  NOT NULL COMMENT 'BIN前缀（6-10位）',
  bank_name  VARCHAR(64)  NOT NULL COMMENT '发卡行全称（中国工商银行）',
  bank_short VARCHAR(32)  NULL     COMMENT '简称（工行）',
  card_type  TINYINT      NOT NULL COMMENT '1借记卡 2贷记卡(信用卡) 3准贷记卡',
  length     TINYINT      NOT NULL COMMENT '标准卡长（16/19）',
  note       VARCHAR(128) NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bin (bin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行卡BIN';
-- Flyway 内置主流 BIN 种子约 60 条（工农中建交邮储招中信浦发平安广发光大民生…）
```

### 3.4 sys_basedata_sync_log（同步日志）

```sql
CREATE TABLE sys_basedata_sync_log (
  id            BIGINT      NOT NULL,
  data_type     VARCHAR(16) NOT NULL COMMENT 'region/phone/bin',
  trigger_type  TINYINT     NOT NULL COMMENT '1手动 2定时',
  status        TINYINT     NOT NULL COMMENT '0进行中 1成功 2失败',
  rows_total    INT         NULL,
  rows_inserted INT         NULL,
  rows_updated  INT         NULL,
  rows_disabled INT         NULL COMMENT 'region同步中撤销置0的行数',
  message       VARCHAR(512) NULL,
  started_at    DATETIME    NOT NULL,
  finished_at   DATETIME    NULL,
  PRIMARY KEY (id),
  KEY idx_type_time (data_type, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基础数据同步日志（保留最近，定时任务清理90天前）';
```

### 3.5 菜单与权限（幂等 ON DUPLICATE KEY UPDATE）

| id | 父 | 名称 | 权限标识 | 说明 |
| --- | --- | --- | --- | --- |
| 610 | 600 | 证件解析（C，/tools/cert-parser，lucide:id-card，sort 10） | `tools:cert:list` | 工具页 |
| 6101 | 610 | 解析按钮（F） | `tools:cert:exec` | 触发解析 |
| 209 | 200 | 基础数据（C，/system/basedata，lucide:database，sort 9） | `system:basedata:list` | 管理页 |
| 2091 | 209 | 手动同步（F） | `system:basedata:sync` | 触发同步 |
| 2092 | 209 | 数据维护（F） | `system:basedata:edit` | 号段/BIN 增删改 |

> 注意：系统管理段 201-208 已占用，209 空闲；日常工具段 610 空闲。role_id=1 全量授权（INSERT IGNORE）。

### 3.6 sys_config 种子（数据源与开关，config_type='Y'）

| config_key | 默认值 | 说明 |
| --- | --- | --- |
| tools.basedata.region.source-url | gh-proxy 前缀 + `raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/{level}.json` | 4 级分文件（province/city/area/street），源自国家统计局 |
| tools.basedata.phone.source-url | （空 = 仅用内置种子） | 可选外部号段源 |
| tools.basedata.bin.source-url | （空 = 仅用内置种子） | 可选外部 BIN 源 |
| tools.basedata.sync.log-retention-days | 90 | 同步日志保留天数 |

### 3.7 app_job 种子（调度中心）

```sql
INSERT INTO app_job (job_name, job_desc, handler_type, handler_param, cron_expr, ...)
VALUES ('basedata-weekly-sync', '基础数据周同步（区划/号段/BIN）', 'builtin', 'toolsBasedataSyncTask',
        '0 0 4 ? * SUN', ...);
```

---

## 四、后端设计（server-tools 模块，包 com.serverpanel.tools）

### 4.1 证件解析（策略模式，每证件一个解析器）

```
tools/cert/
├── CertController            /api/v1/tools/cert（options / parse）
├── CertParseService         分发到 7 个解析器；规则文本统一下发
├── parser/IdCardParser      身份证（含15→18升位、区划联查、年龄/生肖/星座）
├── parser/PhoneParser       手机号（查 sys_phone_segment）
├── parser/BankCardParser    银行卡（Luhn + 最长前缀匹配 sys_bank_bin）
├── parser/UsccParser        统一社会信用代码（MOD 31 + 区划联查）
├── parser/PlateParser       车牌（省份枚举 + 新能源规则）
├── parser/HmtPermitParser   回乡证（结构解析）
├── parser/TwPermitParser    台胞证（10位旧版 / 18位居住证双格式）
└── dto/                     CertOptionsVO / CertTypeVO / RuleSectionVO / RuleItemVO
                             / CertParseBody / CertParseResultVO / CertFieldVO / 各类型 extra VO
```

**解析器统一接口**：

```java
/** 证件解析器（策略接口，每种证件一个实现） */
public interface CertParser {
    /** 支持的证件类型 key */
    CertType type();
    /** 解析：合法但语义异常（如未收录号段）不抛异常，写入 warn；结构/校验错误返回 valid=false */
    CertParseResultVO parse(String value);
    /** 构造规则文档（options 下发，前端折叠面板渲染） */
    List<RuleSectionVO> rules();
}
```

### 4.2 API 契约

**GET /api/v1/tools/cert/options**（`tools:cert:list`）

```json
{
  "types": [
    {
      "key": "idCard", "name": "身份证", "icon": "lucide:id-card", "needRegion": true,
      "placeholder": "请输入 15 或 18 位身份证号", "samples": ["11010519491231002X"],
      "rules": [
        {"title": "结构", "items": [
          {"label": "地址码", "desc": "第1-6位，GB/T 2260 行政区划代码：前2位省…"},
          {"label": "出生日期码", "desc": "第7-14位，YYYYMMDD，校验真实日历日期"},
          {"label": "顺序码", "desc": "第15-17位，同址同日出生顺序，奇数男偶数女"},
          {"label": "校验码", "desc": "第18位，ISO 7064 MOD 11-2，权重 7 9 10 5 8 4 2…"}
        ]},
        {"title": "校验算法", "items": [...]}
      ]
    }
  ],
  "limits": {"maxValueLength": 64, "regionReady": true}
}
```

**POST /api/v1/tools/cert/parse**（`tools:cert:exec` + `@Audit(recordParams=false)`）

```json
// 请求
{"type": "idCard", "value": "11010519491231002X"}
// 响应 data（统一结构 + 类型扩展字段）
{
  "type": "idCard", "valid": true, "level": "ok",
  "errors": [], "warns": [],
  "fields": [
    {"label": "行政区划", "value": "北京市 市辖区 朝阳区"},
    {"label": "出生日期", "value": "1949-12-31"},
    {"label": "性别", "value": "女"},
    {"label": "年龄", "value": "76 岁（周岁）"},
    {"label": "校验码", "value": "X（校验通过）"}
  ],
  "extra": {"idCard": {"code6": "110105", "sequence": "002", "check": "X",
    "zodiac": "牛", "constellation": "摩羯座", "upgradedFrom15": null}}
}
```

- `level`: `ok`（全部通过）/ `warn`（合法但需注意，如 15 位老证、未收录号段、区划未同步）/ `error`（结构或校验失败）
- **审计**：`@Audit(module="tools", action="cert:parse", recordParams=false)`，**证件号不出现在任何日志**

### 4.3 基础数据管理（server-tools 内 basedata 子包，Controller 挂 /api/v1/system/basedata）

```
tools/basedata/
├── BasedataController        GET region/tree · region/page · phone-segment/page 等
├── RegionService             树懒加载查询 / 分页检索（keyword 命中 code 或 name）
├── PhoneSegmentService       CRUD（重名校验 prefix）
├── BankBinService            CRUD（重名校验 bin）
├── BasedataSyncService       同步编排：拉取→解析→分批UPSERT→旧数据软删→写sync_log
└── entity/ + mapper/ + dto/  SysRegion / SysPhoneSegment / SysBankBin / SyncLog
```

**接口清单**：

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /system/basedata/region/tree | system:basedata:list | 懒加载子节点（parentCode 为空返回省级） |
| GET | /system/basedata/region/page | system:basedata:list | keyword/level/status 分页 |
| GET | /system/basedata/phone-segment/page | system:basedata:list | 号段分页（operator 筛选） |
| POST/PUT/DELETE | /system/basedata/phone-segment… | system:basedata:edit | 号段 CRUD（审计含参） |
| GET | /system/basedata/bank-bin/page | system:basedata:list | BIN 分页（bankName 筛选） |
| POST/PUT/DELETE | /system/basedata/bank-bin… | system:basedata:edit | BIN CRUD（审计含参） |
| GET | /system/basedata/sync/status | system:basedata:list | 各类型最近一次同步（时间/行数/状态）+ 当前行数统计 |
| GET | /system/basedata/sync/log | system:basedata:list | 同步日志分页 |
| POST | /system/basedata/sync/{type} | system:basedata:sync | 手动触发（type=region/phone/bin），异步执行 |

### 4.4 同步机制（复用调度中心 + JobHttpClient）

```
app_job(handler_type='builtin', handler_param='toolsBasedataSyncTask')
   └─ JobDispatcher 新增 builtin 分支：Spring 容器按 bean name 路由
        └─ ToolsBasedataSyncTask（@Component，实现 BuiltinTask 接口）
             └─ 依次执行 region → phone → bin 同步（同一 service，手动触发复用同一逻辑）
```

- **JobHttpClient 增强**：现 1MB 响应上限对 street.json（约 2-3MB）不够，新增带 `maxBytes` 参数的重载（region 同步用 20MB，其余默认 1MB 不变）
- **同步算法（region）**：
  1. 依 `sys_config` 的 source-url 依次拉取 province/city/area/street 四个 JSON
  2. 校验结构与总行数下限（防数据源异常把表清空：拉取行数 < 现有 60% 则中止报错）
  3. 分批 500 行 UPSERT（`ON DUPLICATE KEY UPDATE name/level/parent_code/status`）
  4. 本地存在但新数据集中缺失的 code → `status=0`（软删，旧身份证仍可解析）
  5. 写 `sys_basedata_sync_log`（inserted/updated/disabled 计数）
- **并发护栏**：`AtomicBoolean` 按 type 防重入，进行中再次触发返回 `TOOLS_BD_SYNC_IN_PROGRESS`
- **手动触发**：Controller 直接调用 service（trigger_type=1），与定时任务（trigger_type=2）共享同一实现

### 4.5 错误码（ErrorCode，tools 段顺延 7044~7053）

| 码 | 常量 | 文案 |
| --- | --- | --- |
| 7044 | TOOLS_CERT_TYPE_UNSUPPORTED | 不支持的证件类型 |
| 7045 | TOOLS_CERT_VALUE_INVALID | 证件号码为空或超出长度限制 |
| 7046 | TOOLS_CERT_PARSE_FAILED | 证件号码解析失败（结构不合法） |
| 7047 | TOOLS_CERT_REGION_NOT_READY | 行政区划数据尚未同步，区划解析已降级 |
| 7048 | TOOLS_BD_SEGMENT_DUPLICATE | 号段前缀已存在 |
| 7049 | TOOLS_BD_BIN_DUPLICATE | 银行卡 BIN 已存在 |
| 7050 | TOOLS_BD_SYNC_SOURCE_UNREACHABLE | 基础数据源不可达 |
| 7051 | TOOLS_BD_SYNC_FORMAT_INVALID | 基础数据源格式异常，已中止（防误清空） |
| 7052 | TOOLS_BD_SYNC_IN_PROGRESS | 该类型同步正在进行中 |
| 7053 | TOOLS_BD_SYNC_TIMEOUT | 基础数据同步超时 |

---

## 五、前端设计

### 5.1 证件解析页 `views/tools/cert-parser/index.vue`（菜单 610）

- **结构**：Page + Alert（隐私声明：解析在服务器内存完成、不记录证件号）+ **a-tabs 7 个 Tab**（身份证 / 手机号 / 银行卡 / 统一社会信用代码 / 车牌号 / 回乡证 / 台胞证）
- **每个 Tab**：
  - 输入框（options 下发 placeholder + 示例点击回填）+ 「解析」按钮（6101 权限显隐）
  - 结果卡：结论 Tag（ok=绿 / warn=橙 / error=红）+ 字段表格（label/value，复用 CertFieldVO）
  - 类型扩展区：身份证的「结构图示」（分段着色：地址码/生日/顺序/校验各一色）、银行卡的 Luhn 逐位演算、新能源车牌分段
  - **「构造规则」折叠面板**（a-collapse）：options 下发的 RuleSectionVO 渲染（纯字段列表 + 文案，不引 markdown 引擎）
- 定义 `defineOptions({ name: 'ToolsCertParser' })`，Tailwind 风格同 qrcode/regex 页

### 5.2 基础数据页 `views/system/basedata/index.vue`（菜单 209）

- **Tab1 行政区划**：a-tree 懒加载（省级起步，逐级展开）+ 顶部检索（code/name → 分页表格切换视图）+ 行内 status Tag
- **Tab2 手机号段**：vxe/a-table 分页 + operator 筛选 + 新增/编辑 Modal（prefix/operator/seg_type/note）+ 删除确认（2092 权限）
- **Tab3 银行卡 BIN**：同上（bin/bank_name/card_type/length/note）
- **页首同步状态卡（三枚）**：每类显示「当前行数 · 最近同步时间 · 状态 · 手动刷新按钮（2091 权限）」；region 首次未同步时卡片黄底提示「点击立即同步初始化」
- 同步触发后前端轮询 `/sync/status`（2s 间隔，最多 60 次）刷新结果

### 5.3 API 层

- `api/tools.ts` 追加 `CertApi` 命名空间（types + getCertOptionsApi / parseCertApi）
- 新增 `api/base-data.ts`（region/phoneSegment/bankBin/sync 全套，模式同 quick-nav）

---

## 六、文件变更清单

| 层 | 文件（新增 ⊕ / 修改 ⊗） |
| --- | --- |
| 迁移 | ⊕ V21__cert_parser_and_basedata.sql（菜单 610/209 + 按钮、4 表、号段/BIN 种子、sys_config 4 条、app_job 1 条） |
| 后端 | ⊕ server-tools `cert/`（Controller + Service + 7 Parser + 12 DTO）；⊕ `basedata/`（Controller + 3 Service + SyncService + 3 Entity + 3 Mapper + SyncLog Entity/Mapper + DTO 若干）；⊗ ErrorCode（7044~7053）；⊗ server-appstack `JobDispatcher`（builtin 分支）+ `JobHttpClient`（maxBytes 重载）；⊕ BuiltinTask 接口 + ToolsBasedataSyncTask |
| 前端 | ⊕ views/tools/cert-parser/index.vue；⊕ views/system/basedata/index.vue；⊗ api/tools.ts；⊕ api/base-data.ts |

> 预估规模：后端约 30 个新文件，前端 2 页 + 2 API 文件，迁移 1 个。

## 七、实施顺序（建议四阶段，阶段间可验收）

1. **阶段 1 骨架**：V21 + ErrorCode + cert options/parse + 7 个解析器（区划未同步降级逻辑）+ 工具页前端 → 身份证/手机号/银行卡/信用代码/车牌/港澳台全可解析（区划仅省级）
2. **阶段 2 基础数据管理**：basedata CRUD + 管理页前端（号段/BIN 用种子数据即可用）
3. **阶段 3 同步链路**：JobHttpClient 增强 + 同步 service + builtin handler + 手动触发 + 状态卡 + region 数据初始化 → 证件解析区划升级为 4 级全量
4. **阶段 4 收尾**：同步日志页/清理、审计核对（确认无证件号落库）、验收清单过一遍

## 八、验收清单（要点）

- [ ] 7 种证件解析：每种 ≥ 3 组正例（含边界：闰年生日、X 校验码、新能源车牌、居住证 830000）+ ≥ 2 组反例（错误校验码、含 I/O 车牌、15 位字母位）
- [ ] 身份证 15 位升位正确性（抽 3 例手算校验码核对）
- [ ] 区划未同步时解析降级 + 提示；同步后 4 级联查全量正确（抽 5 个区县）
- [ ] 号段/BIN 种子数据准确（移动/联通/电信/广电主流段、工农中建等主流 BIN）
- [ ] 手动同步：进行中防重入、成功后行数/状态卡刷新、失败信息可读
- [ ] 定时任务：app_job 种子可启停、cron 可改、日志可在 job-log 页查看
- [ ] 安全：审计日志/操作日志中检索不到任何证件号原文；解析接口不写任何表
- [ ] vue-tsc 新增文件 0 错误；JavaDoc 齐全（@author zhaodc @since 2026-09-25）

## 九、风险与不做项

| 项 | 说明 |
| --- | --- |
| 手机号归属地 | 不做（需城市级号段映射大数据，准确性无法承诺） |
| 村级区划（第 5 级） | 本期不做，`level` 字段与代码结构已预留 |
| 港澳台证件校验码 | 官方未公开算法，仅结构解析，页面有明确说明 |
| 发牌机关字母反查城市 | 公开对照不全，仅给惯例说明文案 |
| 外部数据源可用性 | gh-proxy 前缀可配 + 源 URL 可配 + 行数下限护栏 + 失败不清库 |
| 旧区划代码（已撤销） | 软删保留（status=0），老身份证仍可解析出历史地名 |
