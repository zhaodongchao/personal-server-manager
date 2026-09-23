package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.IdDecodeBody;
import com.serverpanel.tools.dto.IdDecodeResultVO;
import com.serverpanel.tools.dto.IdGenerateBody;
import com.serverpanel.tools.dto.IdGenerateResultVO;
import com.serverpanel.tools.dto.IdItemVO;
import com.serverpanel.tools.dto.IdOptionsVO;
import com.serverpanel.tools.dto.IdParamVO;
import com.serverpanel.tools.dto.IdSchemeVO;
import com.serverpanel.tools.dto.IdSegmentVO;
import com.serverpanel.tools.dto.OptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ID 生成器服务：把常用 ID 方案的位分配讲清楚，并真的生成一批可验证的 ID。
 *
 * <p><b>本页的三条硬边界</b>：
 * <ol>
 *   <li><b>不连接任何数据库、不创建任何对象</b>。自增计数器与序列属于「数据库对象状态」，
 *       脱离真实库无法取号，因此这两类做的是<b>参数化模拟</b>：按用户给的起始值、步长、
 *       缓存段推演出号段，并显式标注空洞的成因。真实取号请用 {@code appstack/database}。</li>
 *   <li><b>生成结果不参与任何业务</b>，不落库、不缓存，只回给调用方用于观察。</li>
 *   <li><b>不触碰宿主资源</b>：UUIDv1 的 node 一律不读本机网卡（默认随机 node，符合
 *       RFC 4122 §4.5 的隐私做法），要真实 MAC 由用户自己填。因此本模块只依赖
 *       server-framework。</li>
 * </ol>
 *
 * <p><b>关于时钟回拨</b>：雪花类的最大风险是机器时钟回拨导致重复 ID。本页用
 * {@code clockBackwardMs} 做<b>可控模拟</b>，让用户能直观对比三种主流处置策略
 * （WAIT 等待追平 / REJECT 直接拒绝 / TOLERATE 沿用逻辑时间戳）。模拟<b>不会真的
 * 阻塞线程</b>，等待耗时只在说明里给出。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Service
@RequiredArgsConstructor
public class IdService {

    /** 单次生成数量上限 */
    public static final int MAX_COUNT = 1000;

    /** 单次返回「逐条位段拆解」的行数上限，避免 1000 行的拆解把响应体撑大 */
    public static final int SEG_LIMIT = 100;

    /** 待反解 ID 的字符数上限 */
    private static final int MAX_VALUE_CHARS = 512;

    /** 反解/生成时可接受的合法 ID 字符数上限（ObjectId 为 24，UUID 为 36，冗余给足） */
    private static final int MAX_ID_CHARS = 128;

    /** 1582-10-15 00:00:00 UTC 到 1970-01-01 00:00:00 UTC 的 100ns 间隔数（UUIDv1 的 Gregorian 时间基准） */
    private static final long UUID_V1_EPOCH_100NS = 122_192_928_000_000_000L;

    /** Twitter 雪花默认时间基准：2010-11-04 09:42:54.657 UTC */
    private static final long SNOWFLAKE_EPOCH_MS = 1_288_834_974_657L;

    /** 百度 UidGenerator 原始默认基准 2016-05-20 00:00:00 UTC（28 位秒级时间戳约 8.51 年即用尽，已过时） */
    private static final long UID_GENERATOR_LEGACY_EPOCH_MS = 1_463_673_600_000L;

    /** 百度 UidGenerator 本页默认基准：2026-01-01 00:00:00 UTC（避开原始基准已耗尽的问题） */
    private static final long UID_GENERATOR_EPOCH_MS = 1_767_225_600_000L;

    /** Sonyflake 默认时间基准：2014-09-01 00:00:00 UTC */
    private static final long SONYFLAKE_EPOCH_MS = 1_409_529_600_000L;

    /** 方案编码 */
    private static final String S_MYSQL = "MYSQL_AUTO_INCREMENT";
    private static final String S_SEQUENCE = "SEQUENCE";
    private static final String S_UUID_V1 = "UUID_V1";
    private static final String S_UUID_V4 = "UUID_V4";
    private static final String S_UUID_V7 = "UUID_V7";
    private static final String S_OBJECT_ID = "OBJECT_ID";
    private static final String S_SNOWFLAKE = "SNOWFLAKE";
    private static final String S_UID_GENERATOR = "UID_GENERATOR";
    private static final String S_SONYFLAKE = "SONYFLAKE";

    /** 大类 */
    private static final String G_DB = "DB";
    private static final String G_RANDOM = "RANDOM";
    private static final String G_SNOWFLAKE = "SNOWFLAKE";
    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>();

    /** 角色：前端据此配色 */
    private static final String R_SIGN = "SIGN";
    private static final String R_TIME = "TIME";
    private static final String R_MACHINE = "MACHINE";
    private static final String R_SEQ = "SEQ";
    private static final String R_RANDOM = "RANDOM";
    private static final String R_VERSION = "VERSION";
    private static final String R_VARIANT = "VARIANT";
    private static final String R_COUNTER = "COUNTER";

    /** 形态 */
    private static final String F_NUMBER = "NUMBER";
    private static final String F_UUID = "UUID";
    private static final String F_HEX = "HEX";

    private static final DateTimeFormatter TS_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 全局共享的强随机源（ThreadLocalRandom 不适合 UUID/ObjectId 这类需要密码学强度的场景） */
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        GROUP_LABELS.put(G_DB, "数据库原生自增类");
        GROUP_LABELS.put(G_RANDOM, "随机 / 时间哈希类");
        GROUP_LABELS.put(G_SNOWFLAKE, "雪花及其变种");
    }

    /** 静态方案清单（不可变，只在类加载时构建一次） */
    private final Map<String, IdSchemeVO> schemes = buildSchemes();

    // ==========================================================================
    // 一、可选清单
    // ==========================================================================

    /** 方案清单与数量上限 */
    public IdOptionsVO options() {
        IdOptionsVO vo = new IdOptionsVO();
        vo.setSchemes(new ArrayList<>(schemes.values()));
        List<OptionVO> groups = new ArrayList<>();
        GROUP_LABELS.forEach((k, v) -> groups.add(new OptionVO(k, v)));
        vo.setGroups(groups);
        vo.setMaxCount(MAX_COUNT);
        vo.setSegLimit(SEG_LIMIT);
        return vo;
    }

    // ==========================================================================
    // 二、方案注册表
    // ==========================================================================

    private Map<String, IdSchemeVO> buildSchemes() {
        Map<String, IdSchemeVO> map = new LinkedHashMap<>();
        put(map, mysqlAutoIncrement());
        put(map, sequence());
        put(map, uuidV1());
        put(map, uuidV4());
        put(map, uuidV7());
        put(map, objectId());
        put(map, snowflake());
        put(map, uidGenerator());
        put(map, sonyflake());
        return map;
    }

    private static void put(Map<String, IdSchemeVO> map, IdSchemeVO scheme) {
        map.put(scheme.getValue(), scheme);
    }

    /** 自增计数器（MySQL AUTO_INCREMENT）：表级计数器，回滚不回收号段 */
    private static IdSchemeVO mysqlAutoIncrement() {
        IdSchemeVO s = base(S_MYSQL, "自增计数器（MySQL AUTO_INCREMENT）", G_DB, F_NUMBER,
            "严格递增（表内唯一）", "数据库端（存储引擎维护）",
            "表级计数器，插入时自动 +1；事务回滚不回收号段，空洞必然存在。", "", 0, false, false);
        s.setPros(List.of(
            "实现零成本：一条 DDL 就能用，不需要任何额外组件",
            "数值紧凑且单调递增，作为聚簇索引主键时写入局部性最好",
            "单表内绝对唯一，不存在冲突重试"));
        s.setCons(List.of(
            "只在表内唯一：多库、多表合并时会撞号",
            "分库分表要手工设步长（步长 = 分片数，起始值 = 分片序号），扩缩容很疼",
            "事务回滚、批量插入预分配、自增锁批量申请都会产生 ID 空洞",
            "ID 暴露数据量，可被外部枚举（爬虫按 id 顺序遍历）",
            "InnoDB 8.0 起自增计数器持久化到 redo log，重启不再回退到 MAX(id)+1（旧版本会）"));
        s.setParams(List.of(
            num("current", "当前计数器值", "1", 1L, Long.MAX_VALUE,
                "模拟「表里已有的最大自增值」，下一个 INSERT 会得到 current + step"),
            num("step", "步长（auto_increment_increment）", "1", 1L, 10_000L,
                "分库分表场景下常设为分片总数，配合起始值把号段错开"),
            num("holeAfter", "在第几个号之后演示空洞", "0", 0L, 1000L,
                "模拟「事务回滚 / 批量插入预分配」丢弃号段：0 表示不演示"),
            num("holeSize", "空洞大小", "0", 0L, 10_000L,
                "被丢弃的号个数；回滚后这些号永久留空，MAX(id)+1 也不会补回来")));
        s.setSample("1, 2, 3, 4, 5 …");
        return s;
    }

    /** 序列 Sequence（PG Identity / Oracle Sequence）：独立对象，可缓存预分配 */
    private static IdSchemeVO sequence() {
        IdSchemeVO s = base(S_SEQUENCE, "序列 Sequence（PG Identity / Oracle Sequence）", G_DB, F_NUMBER,
            "段内递增，全局跳号", "数据库端（独立序列对象）",
            "独立于表的全局序列对象，可用 CACHE 把号段批量预分配到会话，大幅减少取号时的锁竞争。",
            "", 0, false, false);
        s.setPros(List.of(
            "独立于表：可以被多张表共享，表删了序列还在",
            "CACHE 批量预分配后，取号基本不产生跨会话竞争",
            "支持步长、最大值、循环、序类型等精细控制"));
        s.setCons(List.of(
            "CACHE + 多会话并发时，各会话只保证「自己段内连续」，全局必然跳号",
            "会话异常结束或实例重启会丢弃未用完的缓存段，空洞比自增更大",
            "同样是单库唯一，跨库需要额外规划",
            "Oracle 用 RAC 多实例时还要设 ORDER / NOORDER，NOORDER 下序号不保证全局单调"));
        s.setParams(List.of(
            num("start", "起始值（START WITH）", "1", 1L, Long.MAX_VALUE, "序列第一次取到的值"),
            num("increment", "步长（INCREMENT BY）", "1", 1L, 100_000L, "每次取号累加的值，也是分片错开号段的手段"),
            num("cache", "缓存段大小（CACHE n）", "1", 1L, 10_000L,
                "每个会话一次性预分配到 n 个号，用完再申请下一段；设为 1 等价于不缓存"),
            num("sessions", "并发会话数", "1", 1L, 8L,
                "模拟几个会话同时取号；大于 1 时会看到号段交错，这正是 CACHE 造成跳号的原因")));
        s.setSample("会话 A: 1, 2, 3  会话 B: 1001, 1002, 1003");
        return s;
    }
    /** UUIDv1：60 位 100ns 时间戳 + 14 位时钟序列 + 48 位 MAC */
    private static IdSchemeVO uuidV1() {
        IdSchemeVO s = base(S_UUID_V1, "UUIDv1（时间戳 + MAC 地址）", G_RANDOM, F_UUID,
            "时间有序（近似）", "应用端（本地计算）",
            "60 位 100ns 时间戳 + 14 位时钟序列 + 48 位网卡 MAC；有序但会泄露硬件信息。",
            "32+16+4+12+2+14+48", 128, true, false);
        s.setPros(List.of(
            "时间前缀在前，索引写入有局部性，比 UUIDv4 友好",
            "不需要中心化发号器，本地即可生成",
            "自带生成时间与节点标识，便于排查问题"));
        s.setCons(List.of(
            "直接把网卡 MAC 暴露在 ID 里，隐私与安全隐患明显（RFC 4122 因此建议改用随机 node）",
            "时钟回拨时只能靠 14 位 clock_seq 兜底，回拨跨度大或并发高仍可能重复",
            "同一台机器在同一 100ns 内多次生成需要靠 clock_seq 递增来防冲突",
            "各语言实现质量参差，部分实现并不保证并发安全"));
        s.setSegments(List.of(
            seg("时间低位 time_low", 32, R_TIME, "时间戳最低 32 位"),
            seg("时间中位 time_mid", 16, R_TIME, "时间戳中间 16 位"),
            seg("版本 version", 4, R_VERSION, "固定 0001；它挤在「时间高位」字段的最高 4 位里，所以时间位其实被版本号切开了"),
            seg("时间高位 time_hi", 12, R_TIME, "时间戳最高 12 位"),
            seg("变体 variant", 2, R_VARIANT, "固定 10，表示 RFC 4122 变体"),
            seg("时钟序列 clock_seq", 14, R_COUNTER, "检测到时钟回拨或并发冲突时递增，用来避免重复"),
            seg("节点 node", 48, R_MACHINE, "网卡 MAC；若最高字节的组播位为 1 则表示这里放的是随机 node")));
        s.setParams(List.of(
            str("node", "节点 node（48 位 MAC）", "",
                "填 12 位十六进制（001122334455 或 00:11:22:33:44:55）。留空则生成随机 node 并把组播位置 1"
                    + "（RFC 4122 §4.5 的隐私做法）—— 本页不会去读服务器真实网卡"),
            str("clockSeq", "时钟序列（14 位）", "",
                "0 ~ 16383。留空则随机取一个，用来区分同一节点上的并发生成者"),
            formatSelect("输出格式", "CANONICAL")));
        s.setSample("1d19dad6-ba7b-11ee-8a3f-001122334455");
        return s;
    }

    /** UUIDv4：122 位安全随机数 */
    private static IdSchemeVO uuidV4() {
        IdSchemeVO s = base(S_UUID_V4, "UUIDv4（纯随机）", G_RANDOM, F_UUID,
            "完全无序", "应用端（本地计算）",
            "122 位安全随机数，实现最简单、隐私最好；代价是作为主键时索引写入完全随机。",
            "32+16+4+12+2+62", 128, false, false);
        s.setPros(List.of(
            "无隐私风险：不含时间、不含硬件信息",
            "本地生成，零依赖零协调，任何语言都内置支持",
            "碰撞概率可忽略（122 位随机，需生成约 2.7×10^18 个才有 50% 概率撞一次）"));
        s.setCons(List.of(
            "完全无序：作为 MySQL 聚簇索引主键会造成页分裂与写放大，插入性能随数据量下降",
            "范围查询无法利用主键顺序，随机 IO 多",
            "文本 36 字符（16 字节二进制），比 bigint 大很多，二级索引也跟着变胖"));
        s.setSegments(List.of(
            seg("随机数 a", 32, R_RANDOM, "来自密码学安全随机源"),
            seg("随机数 b", 16, R_RANDOM, ""),
            seg("版本 version", 4, R_VERSION, "固定 0100"),
            seg("随机数 c", 12, R_RANDOM, ""),
            seg("变体 variant", 2, R_VARIANT, "固定 10"),
            seg("随机数 d", 62, R_RANDOM, "最后一段随机位，与前面共同构成 122 位随机")));
        s.setParams(List.of(formatSelect("输出格式", "CANONICAL")));
        s.setSample("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        return s;
    }

    /** UUIDv7：48 位毫秒时间戳 + 74 位随机（RFC 9562） */
    private static IdSchemeVO uuidV7() {
        IdSchemeVO s = base(S_UUID_V7, "UUIDv7（时间前缀 + 随机）", G_RANDOM, F_UUID,
            "时间有序（大体有序）", "应用端（本地计算）",
            "48 位毫秒时间戳打头 + 74 位随机，兼顾索引局部性与隐私，是当前推荐的 UUID 版本（RFC 9562）。",
            "48+4+12+2+62", 128, true, false);
        s.setPros(List.of(
            "时间戳在最高位，天然按生成时间有序，主键索引友好",
            "不需要机器号协调，也不泄露硬件信息（比 UUIDv1 干净）",
            "同毫秒内可用 rand_a 递增实现严格单调（RFC 9562 §6.2 方法 1）",
            "生成时间可反解，便于按时间排查数据"));
        s.setCons(List.of(
            "只有毫秒精度：同一毫秒内仍靠随机数/计数器排序，跨进程不保证全局严格单调",
            "生成时间可见，若业务要求隐藏时间则不合适",
            "时钟回拨时时间戳会后退，可能退化成乱序（需要应用侧兜底）"));
        s.setSegments(List.of(
            seg("时间戳（ms）", 48, R_TIME, "Unix 毫秒，2^48 ms ≈ 8925 年不会溢出"),
            seg("版本 version", 4, R_VERSION, "固定 0111"),
            seg("rand_a", 12, R_RANDOM, "开启「同毫秒单调」时改为计数器，逐条 +1"),
            seg("变体 variant", 2, R_VARIANT, "固定 10"),
            seg("rand_b", 62, R_RANDOM, "其余随机位")));
        s.setParams(List.of(
            bool("monotonic", "同毫秒内单调递增", "true",
                "开启后，同一毫秒内生成的 ID 用 rand_a 做计数器递增，保证批量生成时严格有序"),
            formatSelect("输出格式", "CANONICAL")));
        s.setSample("018f1c1e-9a3b-7c2d-8e4f-0123456789ab");
        return s;
    }

    /** MongoDB ObjectId：4 字节秒时间 + 5 字节随机 + 3 字节计数器 */
    private static IdSchemeVO objectId() {
        IdSchemeVO s = base(S_OBJECT_ID, "MongoDB ObjectId", G_RANDOM, F_HEX,
            "大体有序（秒级）", "应用端（驱动生成）",
            "12 字节（24 位十六进制）：4 字节秒级时间戳 + 5 字节进程随机数 + 3 字节自增计数器，由驱动本地生成。",
            "32+40+24", 96, true, false);
        s.setPros(List.of(
            "12 字节，比 UUID 少 4 字节，索引与存储更省",
            "首 4 字节是秒级时间戳，大体有序，索引局部性还不错",
            "驱动本地生成，无中心化发号器；自带时间信息便于排查"));
        s.setCons(List.of(
            "只有秒级精度，同一秒内完全靠 3 字节计数器排序",
            "3 字节计数器上限 16777215，单进程每秒最多生成约 1677 万个（超出会进位到下一秒）",
            "仍会暴露大致生成时间",
            "5 字节随机段的语义（机器+进程 / 纯随机）在不同驱动版本里不一致，跨驱动不保证可比性"));
        s.setSegments(List.of(
            seg("时间戳（秒）", 32, R_TIME, "Unix 秒，大端；2^32 秒 ≈ 136 年到 2106 年"),
            seg("随机数（机器+进程）", 40, R_RANDOM, "官方规范为机器标识 + 进程 ID 各若干字节，现代驱动一律用随机数"),
            seg("自增计数器", 24, R_COUNTER, "大端，初值随机，每次 +1，用来保证同秒内唯一")));
        s.setParams(List.of(
            str("random5", "随机段（10 位十六进制）", "",
                "填 10 位十六进制即 5 字节。留空则随机生成，用于模拟「同一台机器同一进程」的固定值"),
            str("counterStart", "计数器初值（十进制）", "",
                "0 ~ 16777215。留空则随机取初值，与官方驱动一致")));
        s.setSample("507f1f77bcf86cd799439011");
        return s;
    }
    /** Twitter Snowflake：1+41+10+12 */
    private static IdSchemeVO snowflake() {
        IdSchemeVO s = base(S_SNOWFLAKE, "Snowflake（Twitter 雪花）", G_SNOWFLAKE, F_NUMBER,
            "单调递增（毫秒级）", "应用端（本地计算）",
            "64 位切成四段：符号位 1 + 毫秒时间戳 41 + 机器 ID 10 + 序列号 12，纯本地计算、无需协调。",
            "1+41+10+12", 64, true, true);
        s.setPros(List.of(
            "64 位正整数，数据库用 bigint 直接存，索引与存储都友好",
            "毫秒级单调递增，主键插入有局部性",
            "本地生成零网络开销，单节点每毫秒 4096 个（约 409.6 万/秒）",
            "结构清晰，几乎所有语言都有成熟实现"));
        s.setCons(List.of(
            "强依赖机器时钟：时钟回拨会直接产生重复 ID，这是最致命的风险",
            "机器 ID 需要统一分配并维护（10 位最多 1024 个节点），扩容、换机、容器漂移都要小心",
            "41 位毫秒从 2010-11-04 起算，约 69.7 年后（2080 年前后）用尽",
            "序列号只有 12 位，超过 4096/毫秒必须等下一毫秒，突发流量下会有毛刺",
            "Twitter 原版把 10 位机器 ID 拆成数据中心 5 位 + 工作节点 5 位；本页按常见简化为单个 10 位机器 ID"));
        s.setSegments(List.of(
            seg("符号位 sign", 1, R_SIGN, "恒为 0，保证 ID 是正数，等价于「按无符号看」"),
            seg("时间戳（ms）", 41, R_TIME, "当前时间与时间基准之差，单位毫秒；41 位 ≈ 69.7 年"),
            seg("机器 ID", 10, R_MACHINE, "0 ~ 1023；原版拆为数据中心 5 位 + 工作节点 5 位"),
            seg("序列号", 12, R_SEQ, "同一毫秒内的自增序号，0 ~ 4095，溢出则等待下一毫秒")));
        s.setParams(List.of(
            num("epoch", "时间基准 epoch（毫秒）", String.valueOf(SNOWFLAKE_EPOCH_MS), 0L, 4_102_444_800_000L,
                "默认 1288834974657 = 2010-11-04 09:42:54.657 UTC（Twitter 原版）。改小可延长可用年限"),
            num("machineId", "机器 ID", "1", 0L, 1023L, "0 ~ 1023，不同节点必须不同，否则同一毫秒会撞号"),
            num("clockBackwardMs", "模拟时钟回拨（毫秒）", "0", 0L, 60_000L,
                "大于 0 即模拟「生成前机器时钟往回跳了 N 毫秒」，用来观察三种处置策略的差别"),
            backwardStrategySelect()));
        s.setSample("1541815603606036480");
        return s;
    }

    /** 百度 UidGenerator（DefaultUidGenerator 位分配）：1+28+22+13 */
    private static IdSchemeVO uidGenerator() {
        IdSchemeVO s = base(S_UID_GENERATOR, "UidGenerator（百度，雪花增强）", G_SNOWFLAKE, F_NUMBER,
            "单调递增（秒级）", "应用端（本地计算）",
            "把时间位压到 28 位秒级、工作节点位扩到 22 位：单秒 8192 个序列、节点容量极大，代价是时间位只够约 8.5 年。",
            "1+28+22+13", 64, true, true);
        s.setPros(List.of(
            "工作节点 ID 22 位 → 最多 4194304 个实例，容器化大规模部署也不用愁号段分配",
            "把省下的毫秒位让给节点与序列，节点容量换时间精度，取舍明确",
            "DefaultUidGenerator 实现简单：同一秒内序列号自增，溢出则等到下一秒",
            "CachedUidGenerator 用 RingBuffer 预生成，官方称单机吞吐可达百万级"));
        s.setCons(List.of(
            "时间位只有 28 位：2^28 秒 ≈ 8.51 年就把时间戳用尽 —— 百度原始基准 2016-05-20 在 2024 年底前后已耗尽，"
                + "继续用会溢出成错误 ID，这是该方案最容易踩的坑",
            "秒级精度，同一秒内靠 13 位序列（8192/秒）排序",
            "同样依赖机器时钟，回拨需自行处理（CachedUidGenerator 的 RingBuffer 也挡不住时钟回拨导致的重复）",
            "RingBuffer 预生成要额外的内存与预热时间，且「生产跟不上消费」时会阻塞等待"));
        s.setSegments(List.of(
            seg("符号位 sign", 1, R_SIGN, "恒为 0"),
            seg("时间戳（秒）", 28, R_TIME, "相对时间基准的秒数；2^28 秒 ≈ 8.51 年，这是它最大的限制"),
            seg("工作节点 ID", 22, R_MACHINE, "0 ~ 4194303，容量远大于雪花的 10 位"),
            seg("序列号", 13, R_SEQ, "同一秒内的自增序号，0 ~ 8191")));
        s.setParams(List.of(
            num("epoch", "时间基准 epoch（毫秒）", String.valueOf(UID_GENERATOR_EPOCH_MS), 0L, 4_102_444_800_000L,
                "本页默认 1767225600000 = 2026-01-01 00:00:00 UTC。百度原始基准 1463673600000（2016-05-20）的 28 位秒"
                    + "已经用尽，选它会被直接拒绝并提示溢出"),
            num("workerId", "工作节点 ID", "1", 0L, 4_194_303L, "0 ~ 4194303"),
            num("clockBackwardMs", "模拟时钟回拨（毫秒）", "0", 0L, 60_000L, "含义同雪花的同名参数"),
            backwardStrategySelect()));
        s.setSample("1853980653129474048");
        return s;
    }

    /** Sonyflake：1+39+8+16 */
    private static IdSchemeVO sonyflake() {
        IdSchemeVO s = base(S_SONYFLAKE, "Sonyflake（索尼，雪花变种）", G_SNOWFLAKE, F_NUMBER,
            "单调递增（10 毫秒级）", "应用端（本地计算）",
            "把时间位拉到 39 位（单位 10 毫秒，可用约 174 年），机器 ID 16 位，序列号压到 8 位。",
            "1+39+8+16", 64, true, true);
        s.setPros(List.of(
            "时间可用年限极长：39 位 × 10 毫秒 ≈ 174 年，基本不用担心时间戳耗尽",
            "机器 ID 16 位，直接取机器私有 IP 的低 16 位即可，不需要中心化的号段分配",
            "位分配调整后结构依然简单，标准库级实现，代码量很小"));
        s.setCons(List.of(
            "序列号只剩 8 位：单个节点每 10 毫秒最多 256 个（约 25600/秒），明显低于雪花",
            "10 毫秒的时间精度比雪花的毫秒粗，ID 之间无法精确排序到毫秒",
            "同样依赖机器时钟，回拨需要应用侧兜底（原版会直接报错）",
            "各实现的机器 ID 取法不统一（私有 IP 低 16 位 / MAC 低 16 位），跨语言迁移要注意"));
        s.setSegments(List.of(
            seg("符号位 sign", 1, R_SIGN, "恒为 0"),
            seg("时间戳（10ms）", 39, R_TIME, "相对时间基准的 10 毫秒数；39 位 ≈ 174 年"),
            seg("序列号", 8, R_SEQ, "同一 10 毫秒内的自增序号，0 ~ 255"),
            seg("机器 ID", 16, R_MACHINE, "0 ~ 65535，原版取私有 IP 的低 16 位")));
        s.setParams(List.of(
            num("epoch", "时间基准 epoch（毫秒）", String.valueOf(SONYFLAKE_EPOCH_MS), 0L, 4_102_444_800_000L,
                "默认 1409529600000 = 2014-09-01 00:00:00 UTC（Sonyflake 原版）"),
            num("machineId", "机器 ID", "1", 0L, 65_535L, "0 ~ 65535"),
            num("clockBackwardMs", "模拟时钟回拨（毫秒）", "0", 0L, 60_000L, "含义同雪花的同名参数"),
            backwardStrategySelect()));
        s.setSample("125342910836955136");
        return s;
    }

    // ==========================================================================
    // 三、定义辅助
    // ==========================================================================

    private static IdSchemeVO base(String value, String label, String group, String shape,
                                   String ordered, String generator, String note,
                                   String bits, int totalBits, boolean timeBased, boolean distributed) {
        IdSchemeVO s = new IdSchemeVO();
        s.setValue(value);
        s.setLabel(label);
        s.setGroup(group);
        s.setGroupLabel(GROUP_LABELS.get(group));
        s.setShape(shape);
        s.setOrdered(ordered);
        s.setGenerator(generator);
        s.setNote(note);
        s.setBits(bits);
        s.setTotalBits(totalBits);
        s.setTimeBased(timeBased);
        s.setDistributed(distributed);
        return s;
    }

    private static IdSegmentVO seg(String name, int width, String role, String note) {
        return new IdSegmentVO(name, width, role, note);
    }

    private static IdParamVO num(String name, String label, String def, Long min, Long max, String help) {
        return new IdParamVO(name, label, "number", true, def, help, min, max, null);
    }

    private static IdParamVO str(String name, String label, String def, String help) {
        return new IdParamVO(name, label, "text", false, def, help, null, null, null);
    }

    private static IdParamVO bool(String name, String label, String def, String help) {
        return new IdParamVO(name, label, "switch", false, def, help, 0L, 1L, null);
    }

    private static IdParamVO sel(String name, String label, String def, String help, List<OptionVO> options) {
        return new IdParamVO(name, label, "select", true, def, help, null, null, options);
    }

    /** UUID 的文本格式选项（三种形态在下游消费方那里兼容性差别很大，值得让用户自己选） */
    private static IdParamVO formatSelect(String label, String def) {
        return sel("format", label, def,
            "CANONICAL 为小写带连字符（标准形态）；UPPER 为大写带连字符；SIMPLE 为去掉连字符的 32 位十六进制",
            List.of(new OptionVO("CANONICAL", "小写带连字符（标准）"),
                new OptionVO("UPPER", "大写带连字符"),
                new OptionVO("SIMPLE", "无连字符 32 位")));
    }

    /** 时钟回拨的三种主流处置策略 */
    private static IdParamVO backwardStrategySelect() {
        return sel("backwardStrategy", "时钟回拨处置策略", "WAIT",
            "仅在「模拟时钟回拨」大于 0 时生效。WAIT：自旋等待时钟追平；REJECT：直接拒绝生成并报错；"
                + "TOLERATE：沿用已发过的逻辑时间戳继续发号（牺牲精度换可用性）",
            List.of(new OptionVO("WAIT", "WAIT 等待时钟追平"),
                new OptionVO("REJECT", "REJECT 直接拒绝"),
                new OptionVO("TOLERATE", "TOLERATE 沿用逻辑时间戳")));
    }
    // ==========================================================================
    // 四、生成入口
    // ==========================================================================

    /** 按方案生成一批 ID（1 ~ {@value #MAX_COUNT} 个） */
    public IdGenerateResultVO generate(IdGenerateBody body) {
        String code = body.getScheme() == null ? "" : body.getScheme().trim().toUpperCase(Locale.ROOT);
        IdSchemeVO scheme = schemes.get(code);
        if (scheme == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SCHEME_UNSUPPORTED,
                "不支持的 ID 生成方案：" + body.getScheme());
        }
        int count = body.getCount() == null ? 10 : body.getCount();
        if (count < 1 || count > MAX_COUNT) {
            throw new ServiceException(ErrorCode.TOOLS_ID_COUNT_INVALID,
                "生成数量必须在 1 ~ " + MAX_COUNT + " 之间，当前为 " + count);
        }
        Map<String, String> params = body.getParams() == null ? Map.of() : body.getParams();

        IdGenerateResultVO vo = new IdGenerateResultVO();
        vo.setScheme(scheme.getValue());
        vo.setLabel(scheme.getLabel());
        vo.setGroup(scheme.getGroup());
        vo.setGroupLabel(scheme.getGroupLabel());
        vo.setBits(scheme.getBits());
        vo.setTotalBits(scheme.getTotalBits());
        vo.setShape(scheme.getShape());
        vo.setOrdered(scheme.getOrdered());
        vo.setGenerator(scheme.getGenerator());
        vo.setSegments(scheme.getSegments());
        vo.setSegLimit(SEG_LIMIT);
        vo.setNotes(new ArrayList<>());
        vo.setWarnings(new ArrayList<>());

        long begin = System.nanoTime();
        List<IdItemVO> items = switch (code) {
            case S_MYSQL -> genAutoIncrement(count, params, vo);
            case S_SEQUENCE -> genSequence(count, params, vo);
            case S_UUID_V1 -> genUuidV1(count, params, vo);
            case S_UUID_V4 -> genUuidV4(count, params);
            case S_UUID_V7 -> genUuidV7(count, params, vo);
            case S_OBJECT_ID -> genObjectId(count, params, vo);
            case S_SNOWFLAKE -> genSnowflake(count, params, vo);
            case S_UID_GENERATOR -> genUidGenerator(count, params, vo);
            case S_SONYFLAKE -> genSonyflake(count, params, vo);
            default -> throw new ServiceException(ErrorCode.TOOLS_ID_SCHEME_UNSUPPORTED, "方案未实现：" + code);
        };
        vo.setCount(items.size());
        vo.setIds(items);
        vo.setElapsedMs(Math.max(0L, (System.nanoTime() - begin) / 1_000_000L));

        // 逐条位段拆解只给前 SEG_LIMIT 行：1000 行的拆解会把响应体撑到几百 KB，
        // 而界面上也没人会一条条翻到第 900 行去看位段。
        if (items.size() > SEG_LIMIT) {
            for (IdItemVO item : items) {
                item.setSegValues(null);
            }
            vo.setSegValuesIncluded(false);
            vo.getNotes().add("生成条数超过 " + SEG_LIMIT + "，为保证响应体大小，只返回 ID 与时间，"
                + "不再逐条返回位段拆解");
        } else {
            vo.setSegValuesIncluded(true);
        }
        return vo;
    }

    // ==========================================================================
    // 五、数据库原生自增类（参数化模拟，不连库）
    // ==========================================================================

    private List<IdItemVO> genAutoIncrement(int count, Map<String, String> p, IdGenerateResultVO vo) {
        long current = longParam(p, "current", 1L, 1L, Long.MAX_VALUE, "当前计数器值");
        long step = longParam(p, "step", 1L, 1L, 10_000L, "步长");
        long holeAfter = longParam(p, "holeAfter", 0L, 0L, MAX_COUNT, "空洞位置");
        long holeSize = longParam(p, "holeSize", 0L, 0L, 1_000_000L, "空洞大小");

        vo.getNotes().add("自增计数器由存储引擎维护，下一个 INSERT 拿到的值是 MAX(id) + 步长；"
            + "这里从 " + current + " 起、按步长 " + step + " 推演，不连数据库、不建任何表");
        if (step > 1) {
            vo.getNotes().add("步长设为 " + step + " 通常用于分库分表：把步长设为分片总数、"
                + "各分片起始值错开，不同分片就不会撞号");
        }
        if (holeAfter > 0 && holeSize > 0) {
            vo.getNotes().add("已开启空洞演示：第 " + holeAfter + " 个号之后跳过 " + holeSize + " 个");
            vo.getWarnings().add("事务回滚、批量插入预分配、自增锁批量申请都会丢弃号段；"
                + "被丢掉的号永久留空，且 MAX(id)+1 不会把它们补回来 —— 所以自增 ID 一定会有空洞");
        }

        List<IdItemVO> items = new ArrayList<>(count);
        long value = current;
        long pendingHole = 0;
        for (int i = 0; i < count; i++) {
            if (pendingHole > 0) {
                value += pendingHole * step;
            }
            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(String.valueOf(value));
            String extra = i == 0 ? "计数器当前值" : "上一行 + " + step;
            if (pendingHole > 0) {
                extra = "跳号：前面 " + pendingHole + " 个号被回滚丢弃（空洞）";
                pendingHole = 0;
            }
            item.setExtra(extra);
            items.add(item);
            value += step;
            if (holeAfter > 0 && holeSize > 0 && i + 1 == holeAfter) {
                pendingHole = holeSize;
            }
        }
        return items;
    }

    private List<IdItemVO> genSequence(int count, Map<String, String> p, IdGenerateResultVO vo) {
        long start = longParam(p, "start", 1L, 1L, Long.MAX_VALUE, "起始值");
        long increment = longParam(p, "increment", 1L, 1L, 100_000L, "步长");
        long cache = longParam(p, "cache", 1L, 1L, 10_000L, "缓存段大小");
        long sessions = longParam(p, "sessions", 1L, 1L, 8L, "并发会话数");

        vo.getNotes().add("序列是独立于表的对象，START WITH " + start + " / INCREMENT BY " + increment
            + " / CACHE " + cache + "，由 " + sessions + " 个会话并发取号（每个号段按顺序轮流取，"
            + "以体现并发下的交错）");
        if (cache > 1) {
            vo.getWarnings().add("CACHE " + cache + " 表示会话一次性预分配 " + cache + " 个号，"
                + "用完再申请下一段。因此各会话只保证「自己段内连续」，全局必然跳号");
        }

        long group = sessions * cache;
        long rounds = (count + group - 1) / group;
        long allocated = rounds * group;
        if (allocated > count && sessions * cache > 1) {
            vo.getWarnings().add("本批共预分配 " + allocated + " 个号、实际取用 " + count
                + " 个，剩余 " + (allocated - count) + " 个随会话结束被丢弃 → 又一处空洞。"
                + "会话异常断开或实例重启时，现象与此完全一致");
        }
        if (sessions == 1 && cache == 1) {
            vo.getNotes().add("会话数为 1、缓存为 1 时，序列就是严格连续的："
                + start + ", " + (start + increment) + ", …");
        }

        List<IdItemVO> items = new ArrayList<>(count);
        for (int r = 0; r < count; r++) {
            long s = r % sessions;
            long t = r / sessions;
            long k = t / cache;
            long j = t % cache;
            long segStart = start + (k * sessions + s) * cache * increment;
            long value = segStart + j * increment;
            IdItemVO item = new IdItemVO();
            item.setIndex(r + 1);
            item.setValue(String.valueOf(value));
            item.setExtra("会话 " + (s + 1) + "，缓存段 #" + (k + 1) + "（段起始 " + segStart + "）"
                + (j == 0 ? "，刚申请到新段" : "，段内第 " + (j + 1) + " 个"));
            items.add(item);
        }
        return items;
    }
    // ==========================================================================
    // 六、随机 / 时间哈希类
    // ==========================================================================

    private List<IdItemVO> genUuidV1(int count, Map<String, String> p, IdGenerateResultVO vo) {
        String nodeHex = strParam(p, "node");
        Long clockSeqOpt = optLong(p, "clockSeq", 0L, 16_383L, "时钟序列");
        String format = formatParam(p);

        byte[] node = nodeBytes(nodeHex, vo);
        int clockSeq = clockSeqOpt == null ? RANDOM.nextInt(16_384) : clockSeqOpt.intValue();
        long baseMs = System.currentTimeMillis();
        vo.getNotes().add("时间戳是「1582-10-15 00:00:00 UTC 起的 100 纳秒数」，60 位，"
            + "本批以 " + fmt(baseMs) + " 为基准，每条 +1 个 100ns（模拟真实实现的时间戳最小步进）");
        vo.getNotes().add("时钟序列取 " + clockSeq + "（14 位）。它的作用就是：时钟回拨、"
            + "或同一 100ns 内重复发号时，靠它换一个值来避免重复");

        List<IdItemVO> items = new ArrayList<>(count);
        long time = UUID_V1_EPOCH_100NS + baseMs * 10_000L;
        for (int i = 0; i < count; i++) {
            long t = time + i;
            byte[] b = new byte[16];
            putInt(b, 0, (int) (t & 0xFFFFFFFFL));
            putShort(b, 4, (int) ((t >>> 32) & 0xFFFFL));
            putShort(b, 6, (int) ((t >>> 48) & 0x0FFFL) | 0x1000);
            b[8] = (byte) (0x80 | ((clockSeq >>> 8) & 0x3F));
            b[9] = (byte) (clockSeq & 0xFF);
            System.arraycopy(node, 0, b, 10, 6);

            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(uuidOf(b, format));
            item.setTime(fmt(baseMs));
            item.setExtra("时间戳 " + t + "（100ns 计数）");
            item.setSegValues(sv(hex(b, 0, 4), hex(b, 4, 2), "0001", hex(b, 6, 2).substring(1),
                "10", String.valueOf(clockSeq), hex(b, 10, 6)));
            items.add(item);
        }
        return items;
    }

    private List<IdItemVO> genUuidV4(int count, Map<String, String> p) {
        String format = formatParam(p);
        List<IdItemVO> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] b = new byte[16];
            RANDOM.nextBytes(b);
            b[6] = (byte) ((b[6] & 0x0F) | 0x40);
            b[8] = (byte) ((b[8] & 0x3F) | 0x80);

            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(uuidOf(b, format));
            item.setExtra("122 位安全随机数");
            item.setSegValues(sv(hex(b, 0, 4), hex(b, 4, 2), "0100", hex(b, 6, 2).substring(1),
                "10", hex(b, 8, 8)));
            items.add(item);
        }
        return items;
    }

    private List<IdItemVO> genUuidV7(int count, Map<String, String> p, IdGenerateResultVO vo) {
        boolean monotonic = boolParam(p, "monotonic", true);
        String format = formatParam(p);
        long ms = System.currentTimeMillis();
        int randA = monotonic ? 0 : RANDOM.nextInt(4096);

        vo.getNotes().add("48 位毫秒时间戳 + 4 位版本 + 12 位 rand_a + 2 位 variant + 62 位 rand_b，共 128 位");
        if (monotonic) {
            vo.getNotes().add("已开启同毫秒单调：rand_a 当作计数器逐条 +1，"
                + "计数器溢出（超过 4095）时时间戳进位到下一毫秒 —— 这就是 RFC 9562 §6.2 的方法 1");
            if (count > 4096) {
                vo.getWarnings().add("生成条数超过 4096，同一毫秒的 rand_a 会用满并进位到下一毫秒："
                    + "前 4096 条同一毫秒，之后的每条把毫秒数 +1");
            }
        }

        List<IdItemVO> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (monotonic && randA > 0x0FFF) {
                ms += 1;
                randA = 0;
            }
            byte[] b = new byte[16];
            RANDOM.nextBytes(b);
            putLong48(b, ms);
            b[6] = (byte) (0x70 | ((randA >>> 8) & 0x0F));
            b[7] = (byte) (randA & 0xFF);
            b[8] = (byte) ((b[8] & 0x3F) | 0x80);

            long randB = ((b[8] & 0x3FL) << 56)
                | ((long) (b[9] & 0xFF) << 48) | ((long) (b[10] & 0xFF) << 40)
                | ((long) (b[11] & 0xFF) << 32) | ((long) (b[12] & 0xFF) << 24)
                | ((long) (b[13] & 0xFF) << 16) | ((long) (b[14] & 0xFF) << 8)
                | (b[15] & 0xFFL);

            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(uuidOf(b, format));
            item.setTime(fmt(ms));
            item.setExtra(monotonic ? "rand_a 计数器 = " + randA : "rand_a 为随机值 = " + randA);
            item.setSegValues(sv(String.valueOf(ms), "0111", String.valueOf(randA), "10",
                String.valueOf(randB)));
            items.add(item);
            randA += 1;
        }
        return items;
    }

    private List<IdItemVO> genObjectId(int count, Map<String, String> p, IdGenerateResultVO vo) {
        String random5Hex = strParam(p, "random5");
        Long counterOpt = optLong(p, "counterStart", 0L, 16_777_215L, "计数器初值");

        byte[] machine = new byte[5];
        if (random5Hex.isBlank()) {
            RANDOM.nextBytes(machine);
            vo.getNotes().add("5 字节随机段（机器 + 进程）已随机生成：" + hex(machine, 0, 5)
                + "，本批所有 ID 共用同一段，符合「同一驱动实例」的行为");
        } else {
            machine = hexToBytes(random5Hex, 5, "随机段必须是 10 位十六进制（5 字节）");
        }
        long counterStart = counterOpt == null ? RANDOM.nextInt(16_777_216) : counterOpt;
        vo.getNotes().add("3 字节计数器从 " + counterStart + " 起逐条 +1，上限 16777215；"
            + "溢出时真实驱动会把时间戳 +1 秒并把计数器归零");
        vo.getNotes().add("12 字节 = 4 字节秒级时间戳 + 5 字节随机 + 3 字节计数器，"
            + "用 24 位十六进制表示");

        long sec = System.currentTimeMillis() / 1000L;
        List<IdItemVO> items = new ArrayList<>(count);
        long counter = counterStart;
        for (int i = 0; i < count; i++) {
            if (counter > 16_777_215L) {
                sec += 1;
                counter = 0;
                vo.getWarnings().add("计数器在第 " + (i + 1) + " 条时溢出（超过 16777215），"
                    + "已按真实驱动行为把时间戳 +1 秒、计数器归零");
            }
            byte[] b = new byte[12];
            putInt(b, 0, (int) sec);
            System.arraycopy(machine, 0, b, 4, 5);
            b[9] = (byte) ((counter >>> 16) & 0xFF);
            b[10] = (byte) ((counter >>> 8) & 0xFF);
            b[11] = (byte) (counter & 0xFF);

            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(hex(b, 0, 12));
            item.setTime(fmt(sec * 1000L));
            item.setExtra("计数器 = " + counter + (counter > 16_777_215L - 5 ? "（接近上限）" : ""));
            item.setSegValues(sv(String.valueOf(sec), hex(machine, 0, 5), String.valueOf(counter)));
            items.add(item);
            counter += 1;
        }
        return items;
    }
    // ==========================================================================
    // 七、雪花及其变种
    // ==========================================================================

    private List<IdItemVO> genSnowflake(int count, Map<String, String> p, IdGenerateResultVO vo) {
        long epoch = longParam(p, "epoch", SNOWFLAKE_EPOCH_MS, 0L, 4_102_444_800_000L, "时间基准");
        long machineId = longParam(p, "machineId", 1L, 0L, 1023L, "机器 ID");
        long delta = deltaOf(p, epoch, (1L << 41), "41 位毫秒时间戳", vo);

        vo.getNotes().add("位分配：符号位 1 + 时间戳 41 + 机器 ID 10 + 序列号 12 = 64 位，"
            + "机器 ID = " + machineId + "，时间基准 " + fmt(epoch) + "，当前偏移 " + delta + " 毫秒");
        vo.getNotes().add("单节点容量：每毫秒 4096 个（12 位序列），即理论上限约 409.6 万/秒；"
            + "超出后必须等下一毫秒，突发流量下会出现毛刺");
        vo.getNotes().add("41 位毫秒可用约 69.7 年，用尽时间约 " + fmt(epoch + (1L << 41)));
        if (machineId > 1000) {
            vo.getWarnings().add("机器 ID 已接近 10 位上限 1023，扩容时很容易越界");
        }

        List<IdItemVO> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seq = i % 4096L;
            long ts = delta + i / 4096L;
            long id = (ts << 22) | (machineId << 12) | seq;
            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(String.valueOf(id));
            item.setHex(String.format("%016x", id));
            item.setTime(fmt(epoch + ts));
            item.setExtra("毫秒偏移 " + ts + "，序列号 " + seq
                + (i > 0 && i % 4096L == 0 ? "（序列溢出，已进位到下一毫秒）" : ""));
            item.setSegValues(sv("0", String.valueOf(ts), String.valueOf(machineId), String.valueOf(seq)));
            items.add(item);
        }
        return items;
    }

    private List<IdItemVO> genUidGenerator(int count, Map<String, String> p, IdGenerateResultVO vo) {
        long epoch = longParam(p, "epoch", UID_GENERATOR_EPOCH_MS, 0L, 4_102_444_800_000L, "时间基准");
        long workerId = longParam(p, "workerId", 1L, 0L, 4_194_303L, "工作节点 ID");
        if (epoch == UID_GENERATOR_LEGACY_EPOCH_MS) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                "百度原始基准 1463673600000（2016-05-20 UTC）的 28 位秒级时间戳已经用尽："
                    + "2^28 秒 ≈ 8.51 年，该基准在 2024 年底前后就已耗尽，继续使用会产生溢出的错误 ID。"
                    + "请改用更近的时间基准（本页默认 2026-01-01 UTC）");
        }
        long deltaSec = deltaOf(p, epoch, (1L << 28), "28 位秒级时间戳", vo);

        vo.getNotes().add("位分配：符号位 1 + 时间戳 28（秒） + 工作节点 22 + 序列号 13 = 64 位，"
            + "工作节点 ID = " + workerId + "，时间基准 " + fmt(epoch) + "，当前偏移 " + deltaSec + " 秒");
        vo.getNotes().add("单节点容量：每 13 位序列 8192 个/秒；节点容量 2^22 = 4194304 个实例，"
            + "这是它相比雪花最主要的优势");
        vo.getNotes().add("代价是时间位只有 28 位：2^28 秒 ≈ 8.51 年，"
            + "该基准用尽时间约 " + fmt(epoch + ((1L << 28) * 1000L)) + "，到期前必须换基准或换方案");

        List<IdItemVO> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seq = i % 8192L;
            long ts = deltaSec + i / 8192L;
            long id = (ts << 35) | (workerId << 13) | seq;
            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(String.valueOf(id));
            item.setHex(String.format("%016x", id));
            item.setTime(fmt(epoch + ts * 1000L));
            item.setExtra("秒偏移 " + ts + "，序列号 " + seq
                + (i > 0 && i % 8192L == 0 ? "（序列溢出，已进位到下一秒）" : ""));
            item.setSegValues(sv("0", String.valueOf(ts), String.valueOf(workerId), String.valueOf(seq)));
            items.add(item);
        }
        return items;
    }

    private List<IdItemVO> genSonyflake(int count, Map<String, String> p, IdGenerateResultVO vo) {
        long epoch = longParam(p, "epoch", SONYFLAKE_EPOCH_MS, 0L, 4_102_444_800_000L, "时间基准");
        long machineId = longParam(p, "machineId", 1L, 0L, 65_535L, "机器 ID");
        long delta = deltaOf(p, epoch, (1L << 39), "39 位 10 毫秒时间戳", vo);
        long ticks = delta / 10L;

        vo.getNotes().add("位分配：符号位 1 + 时间戳 39（10ms） + 序列号 8 + 机器 ID 16 = 64 位，"
            + "机器 ID = " + machineId + "，时间基准 " + fmt(epoch) + "，当前 10ms 偏移 " + ticks);
        vo.getNotes().add("时间可用年限：2^39 × 10 毫秒 ≈ 174 年，"
            + "用尽时间约 " + fmt(epoch + (1L << 39) * 10L) + " —— 这是它最大的优点");
        vo.getNotes().add("代价是序列号只有 8 位：单节点每 10 毫秒 256 个（约 2.56 万/秒），"
            + "远低于雪花的 409.6 万/秒");

        List<IdItemVO> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seq = i % 256L;
            long tick = ticks + i / 256L;
            long id = (tick << 24) | (seq << 16) | machineId;
            IdItemVO item = new IdItemVO();
            item.setIndex(i + 1);
            item.setValue(String.valueOf(id));
            item.setHex(String.format("%016x", id));
            item.setTime(fmt(epoch + tick * 10L));
            item.setExtra("10ms 偏移 " + tick + "，序列号 " + seq
                + (i > 0 && i % 256L == 0 ? "（序列溢出，已进位到下一个 10 毫秒）" : ""));
            item.setSegValues(sv("0", String.valueOf(tick), String.valueOf(seq), String.valueOf(machineId)));
            items.add(item);
        }
        return items;
    }

    /**
     * 计算「当前时间相对时间基准的偏移」，并顺带处理时钟回拨模拟。
     *
     * @param p         参数
     * @param epoch     时间基准（毫秒）
     * @param capacity  时间位能表示的最大偏移（2^bits）
     * @param unitName  时间位名称，用于错误文案
     * @param vo        结果载体，用来追加告警与说明
     * @return 相对基准的偏移（毫秒）
     */
    private static long deltaOf(Map<String, String> p, long epoch, long capacity,
                                String unitName, IdGenerateResultVO vo) {
        long now = planClock(p, vo);
        if (now < epoch) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                "当前时间 " + fmt(now) + " 早于时间基准 " + fmt(epoch) + "，偏移为负，无法生成 ID");
        }
        long delta = now - epoch;
        if ("28 位秒级时间戳".equals(unitName)) {
            delta = delta / 1000L;
        }
        if (delta >= capacity) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                unitName + " 已用尽：当前偏移 " + delta + " 已达上限 " + capacity
                    + "，时间基准 " + fmt(epoch) + " 起算的可用区间已过。请改用更近的时间基准");
        }
        return delta;
    }

    /**
     * 时钟回拨的可控模拟。
     *
     * <p>不真的阻塞线程：WAIT 策略下「等待」的耗时只写进说明，否则一个工具页面
     * 可能因为用户填了 60000 毫秒而卡住一分钟。
     *
     * @return 逻辑当前时间（毫秒）
     */
    private static long planClock(Map<String, String> p, IdGenerateResultVO vo) {
        long now = System.currentTimeMillis();
        long backward = longParam(p, "clockBackwardMs", 0L, 0L, 60_000L, "模拟时钟回拨");
        if (backward <= 0) {
            return now;
        }
        String strategy = strParam(p, "backwardStrategy", "WAIT").toUpperCase(Locale.ROOT);
        if ("REJECT".equals(strategy)) {
            throw new ServiceException(ErrorCode.TOOLS_ID_CLOCK_BACKWARD,
                "检测到时钟回拨 " + backward + " 毫秒，已按 REJECT 策略拒绝生成："
                    + "回拨期间同一毫秒的序列号会从头开始，必然与本毫秒内已发出的号重复。"
                    + "真实系统通常直接报错并告警，由运维介入校正时钟");
        }
        if ("TOLERATE".equals(strategy)) {
            vo.getWarnings().add("检测到时钟回拨 " + backward + " 毫秒，已按 TOLERATE 策略"
                + "沿用逻辑时间戳立即发号，不做任何等待");
            vo.getNotes().add("TOLERATE 的后果：ID 里的时间戳大于真实时间（逻辑时间领先物理时间）。"
                + "风险在于——如果进程在这段回拨窗口内重启、lastTimestamp 丢失，"
                + "就会把已经发过的号重新发一遍，产生重复 ID");
        } else {
            vo.getWarnings().add("检测到时钟回拨 " + backward + " 毫秒，已按 WAIT 策略"
                + "等待时钟追平后再发号（本页不会真的阻塞线程，" + backward
                + " 毫秒只体现在这句说明里；真实实现会自旋等到当前时间超过 lastTimestamp）");
            vo.getNotes().add("WAIT 的后果：ID 的时间戳等于真实时间（物理时间与逻辑时间一致），"
                + "代价是回拨越大等待越久，生产上通常配合「回拨超过阈值就报警」");
        }
        vo.getNotes().add("注意：WAIT 与 TOLERATE 在本页产出的 ID 完全一样，"
            + "区别只在「是否让物理时间追上逻辑时间」这一件事上");
        return now;
    }
    // ==========================================================================
    // 八、反解
    // ==========================================================================

    /** 按方案反解一个 ID：拆出位段、还原生成时间、给出人类可读的事实 */
    public IdDecodeResultVO decode(IdDecodeBody body) {
        String code = body.getScheme() == null ? "" : body.getScheme().trim().toUpperCase(Locale.ROOT);
        IdSchemeVO scheme = schemes.get(code);
        if (scheme == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SCHEME_UNSUPPORTED,
                "不支持的 ID 生成方案：" + body.getScheme());
        }
        String raw = body.getValue() == null ? "" : body.getValue().trim();
        if (raw.isEmpty() || raw.length() > MAX_VALUE_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_ID_VALUE_INVALID,
                "待反解的 ID 不能为空，且长度不能超过 " + MAX_VALUE_CHARS + " 字符");
        }
        if (raw.length() > MAX_ID_CHARS && !F_NUMBER.equals(scheme.getShape())) {
            throw new ServiceException(ErrorCode.TOOLS_ID_VALUE_INVALID,
                "待反解的 ID 过长（" + raw.length() + " 字符）：UUID 36 字符、ObjectId 24 字符，"
                    + "都不会超过 " + MAX_ID_CHARS + " 字符");
        }

        IdDecodeResultVO vo = new IdDecodeResultVO();
        vo.setScheme(scheme.getValue());
        vo.setLabel(scheme.getLabel());
        vo.setSegments(scheme.getSegments());
        vo.setFacts(new ArrayList<>());
        Long epoch = body.getEpoch();
        switch (code) {
            case S_MYSQL, S_SEQUENCE -> decodeNumber(raw, vo);
            case S_UUID_V1 -> decodeUuid(raw, vo, 1);
            case S_UUID_V4 -> decodeUuid(raw, vo, 4);
            case S_UUID_V7 -> decodeUuid(raw, vo, 7);
            case S_OBJECT_ID -> decodeObjectId(raw, vo);
            case S_SNOWFLAKE -> decodeSnowflake(raw, vo, epoch == null ? SNOWFLAKE_EPOCH_MS : epoch);
            case S_UID_GENERATOR -> decodeUidGenerator(raw, vo, epoch == null ? UID_GENERATOR_EPOCH_MS : epoch);
            case S_SONYFLAKE -> decodeSonyflake(raw, vo, epoch == null ? SONYFLAKE_EPOCH_MS : epoch);
            default -> throw new ServiceException(ErrorCode.TOOLS_ID_SCHEME_UNSUPPORTED, "方案未实现：" + code);
        }
        return vo;
    }

    private static void decodeNumber(String raw, IdDecodeResultVO vo) {
        String digits = raw.replace(",", "").replace(" ", "");
        vo.setValue(digits);
        if (!digits.matches("\\d{1,20}")) {
            vo.setValid(false);
            vo.setReason("自增 / 序列类方案的 ID 是十进制正整数，当前输入不是纯数字。"
                + "（注意：自增与序列的 ID 里不含任何位段信息，无法反解出时间或节点）");
            return;
        }
        vo.setValid(true);
        vo.setReason("结构与「数据库原生自增类」一致：十进制正整数，没有任何位段可拆");
        vo.getFacts().add("十进制位数：" + digits.length());
        vo.getFacts().add("这类 ID 不含时间、不含机器信息，无法反解出生成时间与节点");
        if (digits.length() > 15) {
            vo.getFacts().add("⚠ 超过 15 位（2^53 = 9007199254740992）："
                + "在 JavaScript 里用 Number 承接会丢精度，接口与前端必须按字符串传递");
        } else {
            vo.getFacts().add("未超过 2^53，在 JavaScript 里可用 Number 安全承接");
        }
    }

    private static void decodeUuid(String raw, IdDecodeResultVO vo, int expectVersion) {
        String compact = raw.replace("-", "").replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
        vo.setValue(raw.trim());
        if (!compact.matches("[0-9a-f]{32}")) {
            vo.setValid(false);
            vo.setReason("UUID 必须是 32 位十六进制（标准写法 8-4-4-4-12，含连字符共 36 字符）");
            return;
        }
        String canonical = compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
            + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20);
        vo.setValue(canonical);

        int version = Character.digit(compact.charAt(12), 16);
        int byte16 = Integer.parseInt(compact.substring(16, 18), 16);
        int byte17 = Integer.parseInt(compact.substring(18, 20), 16);
        int variant = byte16 >> 6;
        String variantBits = variant == 2 ? "10" : Integer.toBinaryString(variant);
        boolean versionOk = version == expectVersion;
        boolean variantOk = variant == 2;
        vo.setValid(versionOk && variantOk);
        if (!versionOk) {
            vo.setReason("版本位是 " + Integer.toBinaryString(version) + "（" + version + "），而 UUIDv"
                + expectVersion + " 的版本位固定为 " + Integer.toBinaryString(expectVersion)
                + " —— 这个 ID 不是 UUIDv" + expectVersion);
        } else if (!variantOk) {
            vo.setReason("变体位不是 RFC 4122 的 10，这个 ID 不符合 RFC 4122 变体规范");
        } else {
            vo.setReason("版本位与变体位都与 UUIDv" + expectVersion + " 一致");
        }

        List<String> values = new ArrayList<>();
        values.add(compact.substring(0, 8));
        values.add(compact.substring(8, 12));
        values.add(pad4(Integer.toBinaryString(version)));
        values.add(compact.substring(13, 16));
        values.add(variantBits);

        if (expectVersion == 1) {
            long timeLow = Long.parseLong(compact.substring(0, 8), 16);
            long timeMid = Long.parseLong(compact.substring(8, 12), 16);
            long timeHi = Long.parseLong(compact.substring(13, 16), 16);
            long t = (timeHi << 48) | (timeMid << 32) | timeLow;
            long unixMs = (t - UUID_V1_EPOCH_100NS) / 10_000L;
            int clockSeq = ((byte16 & 0x3F) << 8) | byte17;
            String node = compact.substring(20, 32);
            boolean randomNode = (Integer.parseInt(compact.substring(20, 22), 16) & 0x01) == 1;
            values.add(String.valueOf(clockSeq));
            values.add(node);
            vo.setTime(fmt(unixMs));
            vo.getFacts().add("时间戳（1582-10-15 起的 100 纳秒计数）= " + t);
            vo.getFacts().add("还原出生成时间 = " + fmt(unixMs));
            vo.getFacts().add("时钟序列 = " + clockSeq);
            vo.getFacts().add("节点 = " + node + "，最高字节的组播位为 "
                + (randomNode ? "1 → 这是随机 node（RFC 4122 §4.5 的隐私做法）" : "0 → 看起来是真实 MAC 地址"));
            if (!randomNode) {
                vo.getFacts().add("⚠ 这个 ID 把真实 MAC 暴露给了所有拿到它的人，"
                    + "作为对外暴露的 ID 使用有隐私风险");
            }
        } else if (expectVersion == 4) {
            values.add(compact.substring(16, 32));
            vo.getFacts().add("UUIDv4 全部是随机数，不含任何时间或节点信息，无法反解生成时间");
            vo.getFacts().add("随机位数：122 位（128 位里扣掉 4 位版本 + 2 位变体）");
        } else {
            long ms = Long.parseLong(compact.substring(0, 12), 16);
            long randA = Long.parseLong(compact.substring(13, 16), 16);
            long randB = Long.parseUnsignedLong(compact.substring(16, 32), 16) & ((1L << 62) - 1);
            values.add(String.valueOf(randB));
            vo.setTime(fmt(ms));
            vo.getFacts().add("时间戳（Unix 毫秒）= " + ms);
            vo.getFacts().add("还原出生成时间 = " + fmt(ms));
            vo.getFacts().add("rand_a = " + randA
                + "（开启同毫秒单调时它是计数器，否则是随机值，单看 ID 无法区分）");
            vo.getFacts().add("rand_b = " + randB);
            vo.getFacts().add("48 位毫秒时间戳在同基准下可用约 8925 年，不存在耗尽问题");
        }
        vo.setSegValues(values);
    }

    private static void decodeObjectId(String raw, IdDecodeResultVO vo) {
        String compact = raw.replace("-", "").replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
        vo.setValue(raw.trim());
        if (!compact.matches("[0-9a-f]{24}")) {
            vo.setValid(false);
            vo.setReason("ObjectId 是 12 字节，必须写成 24 位十六进制");
            return;
        }
        vo.setValue(compact);
        long sec = Long.parseLong(compact.substring(0, 8), 16);
        String machine = compact.substring(8, 18);
        long counter = Long.parseLong(compact.substring(18, 24), 16);
        long ms = sec * 1000L;
        vo.setValid(true);
        vo.setReason("结构与 ObjectId 一致：4 字节秒级时间戳 + 5 字节随机 + 3 字节计数器");
        vo.setTime(fmt(ms));
        vo.setSegValues(sv(String.valueOf(sec), machine, String.valueOf(counter)));
        vo.getFacts().add("时间戳（Unix 秒）= " + sec + "，还原出生成时间 = " + fmt(ms));
        vo.getFacts().add("随机段（机器 + 进程）= " + machine);
        vo.getFacts().add("自增计数器 = " + counter + "（上限 16777215）");
        if (ms > System.currentTimeMillis() + 86_400_000L) {
            vo.getFacts().add("⚠ 这个时间戳在未来（超过当前时间 1 天以上），"
                + "可能是伪造的 ID，或生成它的机器时钟不准");
        }
    }

    private static void decodeSnowflake(String raw, IdDecodeResultVO vo, long epoch) {
        Long id = parseIdNumber(raw, vo);
        if (id == null) {
            return;
        }
        vo.setValue(String.valueOf(id));
        vo.setHex(String.format("%016x", id));
        long ts = id >>> 22;
        long machine = (id >>> 12) & 0x3FFL;
        long seq = id & 0xFFFL;
        vo.setValid(ts < (1L << 41));
        vo.setReason(vo.isValid()
            ? "位段与 Snowflake（1+41+10+12）一致"
            : "时间偏移超出 41 位上限，不是合法的 Snowflake ID");
        vo.setTime(fmt(epoch + ts));
        vo.setSegValues(sv("0", String.valueOf(ts), String.valueOf(machine), String.valueOf(seq)));
        vo.getFacts().add("符号位 = 0（保证结果是正数）");
        vo.getFacts().add("时间戳（毫秒偏移）= " + ts + "，时间基准 " + fmt(epoch));
        vo.getFacts().add("还原出生成时间 = " + fmt(epoch + ts));
        vo.getFacts().add("机器 ID = " + machine + "（上限 1023）");
        vo.getFacts().add("序列号 = " + seq + "，即该毫秒内的第 " + (seq + 1) + " 个");
        vo.getFacts().add("时间位还能用约 " + yearsFromMs((1L << 41) - ts) + " 年");
    }

    private static void decodeUidGenerator(String raw, IdDecodeResultVO vo, long epoch) {
        Long id = parseIdNumber(raw, vo);
        if (id == null) {
            return;
        }
        vo.setValue(String.valueOf(id));
        vo.setHex(String.format("%016x", id));
        long ts = id >>> 35;
        long worker = (id >>> 13) & 0x3FFFFFL;
        long seq = id & 0x1FFFL;
        vo.setValid(ts < (1L << 28));
        vo.setReason(vo.isValid()
            ? "位段与 UidGenerator（1+28+22+13）一致"
            : "时间偏移超出 28 位上限：这个 ID 很可能是用已经耗尽的基准生成的（典型症状就是溢出）");
        vo.setTime(fmt(epoch + ts * 1000L));
        vo.setSegValues(sv("0", String.valueOf(ts), String.valueOf(worker), String.valueOf(seq)));
        vo.getFacts().add("符号位 = 0（保证结果是正数）");
        vo.getFacts().add("时间戳（秒偏移）= " + ts + "，时间基准 " + fmt(epoch));
        vo.getFacts().add("还原出生成时间 = " + fmt(epoch + ts * 1000L));
        vo.getFacts().add("工作节点 ID = " + worker + "（上限 4194303）");
        vo.getFacts().add("序列号 = " + seq + "，即该秒内的第 " + (seq + 1) + " 个（上限 8192）");
        long remain = (1L << 28) - ts;
        vo.getFacts().add("时间位还能用约 " + yearsFromMs(remain * 1000L)
            + " 年 —— 28 位秒级时间戳总共只有约 8.51 年，这是该方案的硬伤");
    }

    private static void decodeSonyflake(String raw, IdDecodeResultVO vo, long epoch) {
        Long id = parseIdNumber(raw, vo);
        if (id == null) {
            return;
        }
        vo.setValue(String.valueOf(id));
        vo.setHex(String.format("%016x", id));
        long tick = id >>> 24;
        long seq = (id >>> 16) & 0xFFL;
        long machine = id & 0xFFFFL;
        vo.setValid(tick < (1L << 39));
        vo.setReason(vo.isValid()
            ? "位段与 Sonyflake（1+39+8+16）一致"
            : "时间偏移超出 39 位上限，不是合法的 Sonyflake ID");
        vo.setTime(fmt(epoch + tick * 10L));
        vo.setSegValues(sv("0", String.valueOf(tick), String.valueOf(seq), String.valueOf(machine)));
        vo.getFacts().add("符号位 = 0（保证结果是正数）");
        vo.getFacts().add("时间戳（10 毫秒偏移）= " + tick + "，时间基准 " + fmt(epoch));
        vo.getFacts().add("还原出生成时间 = " + fmt(epoch + tick * 10L)
            + "（10 毫秒精度，所以末位不可能更细）");
        vo.getFacts().add("序列号 = " + seq + "，即该 10 毫秒内的第 " + (seq + 1) + " 个（上限 256）");
        vo.getFacts().add("机器 ID = " + machine + "（上限 65535）");
        vo.getFacts().add("时间位还能用约 " + yearsFromMs(((1L << 39) - tick) * 10L) + " 年");
    }

    private static Long parseIdNumber(String raw, IdDecodeResultVO vo) {
        String t = raw.replace("_", "").replace(" ", "").trim();
        vo.setValue(t);
        String digits = t;
        int radix = 10;
        if (t.startsWith("0x") || t.startsWith("0X")) {
            digits = t.substring(2);
            radix = 16;
        }
        try {
            long v = Long.parseUnsignedLong(digits, radix);
            if (v < 0) {
                vo.setValid(false);
                vo.setReason("这个值超过了 63 位（最高位为 1）：雪花类 ID 的符号位必须是 0，"
                    + "否则它不是本方案生成的");
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            vo.setValid(false);
            vo.setReason("雪花类的 ID 是 64 位无符号整数的十进制写法（也接受 0x 十六进制），"
                + "当前输入不是合法整数");
            return null;
        }
    }

    // ==========================================================================
    // 九、参数解析与位运算辅助
    // ==========================================================================

    private static String strParam(Map<String, String> p, String name) {
        String v = p.get(name);
        return v == null ? "" : v.trim();
    }

    private static String strParam(Map<String, String> p, String name, String def) {
        String v = strParam(p, name);
        return v.isEmpty() ? def : v;
    }

    private static boolean boolParam(Map<String, String> p, String name, boolean def) {
        String v = strParam(p, name);
        if (v.isEmpty()) {
            return def;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }

    private static long longParam(Map<String, String> p, String name, long def,
                                  long min, long max, String label) {
        String v = strParam(p, name);
        if (v.isEmpty()) {
            return def;
        }
        long parsed;
        try {
            parsed = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                label + " 必须是整数，当前为「" + v + "」");
        }
        if (parsed < min || parsed > max) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                label + " 必须在 " + min + " ~ " + max + " 之间，当前为 " + parsed);
        }
        return parsed;
    }

    private static Long optLong(Map<String, String> p, String name, long min, long max, String label) {
        String v = strParam(p, name);
        if (v.isEmpty()) {
            return null;
        }
        return longParam(p, name, 0L, min, max, label);
    }

    private static String formatParam(Map<String, String> p) {
        String f = strParam(p, "format", "CANONICAL").toUpperCase(Locale.ROOT);
        if (!"CANONICAL".equals(f) && !"UPPER".equals(f) && !"SIMPLE".equals(f)) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                "输出格式只能是 CANONICAL / UPPER / SIMPLE，当前为「" + f + "」");
        }
        return f;
    }

    private static byte[] nodeBytes(String nodeHex, IdGenerateResultVO vo) {
        if (nodeHex.isEmpty()) {
            byte[] node = new byte[6];
            RANDOM.nextBytes(node);
            node[0] = (byte) (node[0] | 0x01);
            vo.getWarnings().add("未指定 node，已生成随机 node " + hex(node, 0, 6)
                + " 并把组播位置 1（RFC 4122 §4.5）：这样 ID 不会泄露服务器网卡 MAC。"
                + "要指定真实 MAC，在「节点 node」里填 12 位十六进制即可");
            return node;
        }
        return hexToBytes(nodeHex, 6, "节点 node 必须是 12 位十六进制（48 位 MAC）");
    }

    private static byte[] hexToBytes(String raw, int expectLen, String label) {
        String compact = raw.replace(":", "").replace("-", "").replace(".", "")
            .replace(" ", "").toLowerCase(Locale.ROOT);
        if (compact.length() != expectLen * 2 || !compact.matches("[0-9a-f]+")) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                label + "，当前为「" + raw + "」（期望 " + (expectLen * 2) + " 位十六进制）");
        }
        byte[] out = new byte[expectLen];
        for (int i = 0; i < expectLen; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void putLong48(byte[] b, long v) {
        b[0] = (byte) ((v >>> 40) & 0xFF);
        b[1] = (byte) ((v >>> 32) & 0xFF);
        b[2] = (byte) ((v >>> 24) & 0xFF);
        b[3] = (byte) ((v >>> 16) & 0xFF);
        b[4] = (byte) ((v >>> 8) & 0xFF);
        b[5] = (byte) (v & 0xFF);
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit((b[off + i] >> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b[off + i] & 0x0F, 16));
        }
        return sb.toString();
    }

    private static String uuidOf(byte[] b, String format) {
        String s = hex(b, 0, 16);
        if ("SIMPLE".equals(format)) {
            return s;
        }
        String canonical = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
            + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
        return "UPPER".equals(format) ? canonical.toUpperCase(Locale.ROOT) : canonical;
    }

    private static String pad4(String binary) {
        StringBuilder sb = new StringBuilder(binary);
        while (sb.length() < 4) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        return TS_MS.format(Instant.ofEpochMilli(ms).atZone(ZONE));
    }

    /** 把毫秒数换算成「约X年」，只用于说明性文案 */
    private static String yearsFromMs(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / (1000.0 * 60 * 60 * 24 * 365.25));
    }

    private static List<String> sv(String... v) {
        return new ArrayList<>(List.of(v));
    }
}
