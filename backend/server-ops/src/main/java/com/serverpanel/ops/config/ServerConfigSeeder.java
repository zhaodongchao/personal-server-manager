package com.serverpanel.ops.config;

import java.time.LocalDateTime;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.serverpanel.framework.mongo.MongoBaseService;
import com.serverpanel.ops.entity.mongo.OpsServerConfigCategory;
import com.serverpanel.ops.entity.mongo.OpsServerConfigItem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务器配置模块的 MongoDB 种子初始化（幂等）。
 *
 * <p>四类配置项的预置参数来自生产调优基线调研（见 {@code psm/design/server-config-design.md} §一）。
 *
 * <h2>预置值策略（重要）</h2>
 * <ul>
 *   <li><b>直接预置值</b>：仅提升上限、或纯粹的安全加固项（改了不会打断在跑的服务）——
 *       例如 somaxconn、tcp backlog、swappiness、kptr_restrict。</li>
 *   <li><b>只给推荐值、预置为空（itemValue=null）</b>：一旦改动可能影响在跑服务或需要额外前提的项——
 *       例如 {@code net.ipv4.conf.all.rp_filter}（Docker/NAT 主机可能断流）、
 *       {@code vm.overcommit_memory}（Redis 依赖 1）、
 *       {@code net.ipv4.tcp_congestion_control=bbr} 与配套的 {@code net.core.default_qdisc}
 *       （本机 tcp_bbr/sch_fq 模块尚未加载，直接写会令 {@code sysctl --system} 失败）、
 *       以及 sshd 的 {@code Port} / {@code PermitRootLogin} / {@code PasswordAuthentication}
 *       （改错会把自己锁在门外）。</li>
 * </ul>
 * 「空值 = 不托管」：这些项在页面上会显示当前生效值与推荐值，但不会写进托管文件，
 * 必须由运维人员显式填值后才会生效。
 *
 * <p>空值语义的另一面：清空已有项的值即等于「交还系统默认」，这是可逆的、非破坏性的。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerConfigSeeder implements ApplicationRunner {

    private static final String COL_CATEGORY = "ops_server_config_category";

    private static final String COL_ITEM = "ops_server_config_item";

    private final MongoBaseService mongo;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seed();
            if (created > 0) {
                log.info("服务器配置种子初始化完成：新建 {} 个配置项", created);
            }
        } catch (RuntimeException e) {
            // 种子失败不能阻断应用启动（Mongo 未就绪时尤其如此）
            log.warn("服务器配置种子初始化失败：{}", e.getMessage());
        }
    }

    private int seed() {
        int created = 0;
        for (CategorySeed c : CATEGORIES) {
            OpsServerConfigCategory cat = mongo.findOne(
                            Query.query(Criteria.where("categoryKey").is(c.key())),
                            OpsServerConfigCategory.class, COL_CATEGORY)
                    .orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (cat == null) {
                cat = new OpsServerConfigCategory();
                cat.setId(new ObjectId().toHexString());
                cat.setCategoryKey(c.key());
                cat.setCreatedAt(now);
            }
            cat.setName(c.name());
            cat.setDescription(c.desc());
            cat.setRiskLevel(c.risk());
            cat.setApplyHint(c.hint());
            cat.setManagedFile(c.file());
            cat.setSort(c.sort());
            cat.setStatus(1);
            cat.setUpdatedAt(now);
            mongo.save(cat, COL_CATEGORY);

            // 已有配置项则不再插手，避免覆盖用户改动
            long exists = mongo.count(
                    Query.query(Criteria.where("categoryKey").is(c.key())), COL_ITEM);
            if (exists > 0) {
                continue;
            }
            int sort = 0;
            for (ItemSeed it : c.items()) {
                sort += 10;
                OpsServerConfigItem row = new OpsServerConfigItem();
                row.setId(new ObjectId().toHexString());
                row.setCategoryKey(c.key());
                row.setItemKey(it.key());
                row.setItemValue(it.value());
                row.setValueType(it.type());
                row.setOptions(it.options());
                row.setRecommended(it.recommended());
                row.setDescription(it.desc());
                row.setSort(sort);
                row.setBuiltin(1);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                mongo.save(row, COL_ITEM);
                created++;
            }
        }
        return created;
    }

    // ==================== 种子定义 ==================== //

    private record ItemSeed(String key, String value, String type, List<String> options,
                            String recommended, String desc) {
    }

    private record CategorySeed(String key, String name, String desc, String file, String risk,
                                String hint, int sort, List<ItemSeed> items) {
    }

    /** 直接预置值（仅提升上限 / 安全加固） */
    private static ItemSeed v(String key, String value, String type, String desc) {
        return new ItemSeed(key, value, type, null, value, desc);
    }

    private static ItemSeed vInt(String key, String value, String desc) {
        return v(key, value, "int", desc);
    }

    /** 只给推荐值、预置为空（改动有前提或风险，需人工显式填值） */
    private static ItemSeed rec(String key, String recommended, String type, String desc) {
        return new ItemSeed(key, null, type, null, recommended, desc);
    }

    /** 只给推荐值 + 枚举选项、预置为空 */
    private static ItemSeed recEnum(String key, String recommended, List<String> options, String desc) {
        return new ItemSeed(key, null, "enum", options, recommended, desc);
    }

    private static final List<CategorySeed> CATEGORIES = List.of(
            new CategorySeed("sysctl", "内核参数",
                    "内核运行参数（网络栈 / 内存 / 文件句柄 / 进程 / 基础安全）。"
                            + "写入 /etc/sysctl.d/99-serverpanel.conf 并由 sysctl --system 即时生效。",
                    "/etc/sysctl.d/99-serverpanel.conf", "L2",
                    "sysctl --system 即时生效，并对后续启动持续有效（托管片段随系统启动自动加载）。",
                    10, List.of(
                    vInt("net.core.somaxconn", "65535", "监听队列上限，防高并发下 SYN 溢出"),
                    vInt("net.core.netdev_max_backlog", "65536", "网卡收包 backlog，防突发丢包"),
                    vInt("net.ipv4.tcp_max_syn_backlog", "65535", "SYN 半连接队列上限"),
                    vInt("net.ipv4.tcp_tw_reuse", "1", "允许快速复用 TIME_WAIT 连接（客户端侧）"),
                    vInt("net.ipv4.tcp_fin_timeout", "30", "FIN_WAIT2 超时，缩短连接回收时间"),
                    vInt("net.core.rmem_max", "16777216", "套接字接收缓冲上限"),
                    vInt("net.core.wmem_max", "16777216", "套接字发送缓冲上限"),
                    v("net.ipv4.tcp_rmem", "4096 87380 16777216", "string",
                            "TCP 接收缓冲 min/default/max"),
                    v("net.ipv4.tcp_wmem", "4096 87380 16777216", "string",
                            "TCP 发送缓冲 min/default/max"),
                    v("net.ipv4.ip_local_port_range", "1024 65535", "string", "本机临时端口范围（低 高）"),
                    vInt("net.ipv4.tcp_syncookies", "1", "SYN flood 防护"),
                    vInt("vm.swappiness", "10", "降低换出倾向（DB 建议 1，通用 Web 建议 10）"),
                    vInt("vm.dirty_ratio", "20", "脏页回写比例上限"),
                    vInt("vm.dirty_background_ratio", "10", "后台脏页回写触发比例"),
                    vInt("vm.max_map_count", "262144", "单进程最大内存映射区（JVM/ES 必备）"),
                    vInt("fs.nr_open", "2097152", "单进程可打开文件数上限"),
                    vInt("fs.inotify.max_user_watches", "524288", "inotify 监视数量上限"),
                    vInt("kernel.pid_max", "4194304", "PID 上限"),
                    vInt("kernel.kptr_restrict", "2", "内核指针暴露收敛（安全加固）"),
                    vInt("kernel.dmesg_restrict", "1", "限制非特权用户读取内核日志（安全加固）"),
                    recEnum("net.ipv4.conf.all.rp_filter", "1", List.of("0", "1"),
                            "反向路径过滤。安全加固推荐 1，但【多网卡 / Docker / NAT 主机】开启后"
                                    + "可能丢包断流，请确认路由对称后再启用"),
                    recEnum("vm.overcommit_memory", "1", List.of("0", "1", "2"),
                            "内存超配策略：0 启发式（默认）/ 1 总是允许（Redis 必需）/ 2 严格不超配。"
                                    + "本机当前为 1，若由 Redis 设置请勿随意改回 0"),
                    rec("net.ipv4.tcp_congestion_control", "bbr", "string",
                            "拥塞控制算法。BBR 需内核支持：本机 tcp_bbr 模块尚未加载，"
                                    + "直接写入会令 sysctl --system 校验失败（可先 modprobe tcp_bbr）"),
                    rec("net.core.default_qdisc", "fq", "string",
                            "默认队列算法，通常与 BBR 配套使用（需内核支持 sch_fq）"),
                    rec("fs.file-max", "2097152", "int",
                            "系统级文件句柄上限。本机当前已是内核允许的最大值，无需下调")
            )),

            new CategorySeed("limits", "资源限制",
                    "用户级资源上限（ulimit / limits.conf）。"
                            + "写入 /etc/security/limits.d/99-serverpanel.conf，仅对新会话生效。",
                    "/etc/security/limits.d/99-serverpanel.conf", "L1",
                    "仅对【新会话 / 新进程】生效，已在运行的服务不会回溯；"
                            + "systemd 服务另需在 unit 中设置 LimitNOFILE。",
                    20, List.of(
                    v("* soft nofile", "65535", "int", "单进程软文件描述符上限"),
                    v("* hard nofile", "1048576", "int", "单进程硬文件描述符上限"),
                    v("* soft nproc", "65535", "int", "单用户软进程/线程数上限"),
                    v("* hard nproc", "65535", "int", "单用户硬进程/线程数上限"),
                    v("* soft memlock", "unlimited", "string",
                            "可锁定内存软上限；Redis / PostgreSQL / Cassandra 等数据库必需"),
                    v("* hard memlock", "unlimited", "string", "可锁定内存硬上限"),
                    v("* soft core", "0", "int", "core dump 软上限（0 = 关闭，排障时可临时调大）"),
                    v("* hard core", "0", "int", "core dump 硬上限")
            )),

            new CategorySeed("sshd", "SSH 配置",
                    "SSH 服务安全加固。写入 /etc/ssh/sshd_config.d/99-serverpanel.conf"
                            + "（经主配置的 Include 生效），改动会重启 sshd。",
                    "/etc/ssh/sshd_config.d/99-serverpanel.conf", "L3",
                    "生效执行 systemctl restart sshd：既有连接保留，但请务必先确认密钥可登录，避免自锁。",
                    30, List.of(
                    rec("Port", "22", "int",
                            "监听端口。改端口是防扫描的常见手段，但【改错会直接失联】，"
                                    + "请确认防火墙与 NAT 转发同步调整后再启用"),
                    recEnum("PermitRootLogin", "prohibit-password",
                            List.of("yes", "no", "prohibit-password", "forced-commands-only"),
                            "root 登录策略：yes / no / prohibit-password / forced-commands-only。"
                                    + "推荐 prohibit-password（仅允许密钥），最严格可设 no"),
                    recEnum("PasswordAuthentication", "no", List.of("yes", "no"),
                            "密码登录。【设为 no 前必须确认已有可用密钥】，否则会把自己锁在门外"),
                    recEnum("X11Forwarding", "no", List.of("yes", "no"), "X11 转发；服务器通常无需开启"),
                    v("PubkeyAuthentication", "yes", "string", "启用公钥登录（推荐保持启用）"),
                    v("UseDNS", "no", "string", "关闭登录时的 DNS 反向解析，显著加快连接速度"),
                    v("GSSAPIAuthentication", "no", "string", "关闭 GSSAPI 认证，避免登录卡顿"),
                    v("MaxAuthTries", "3", "int", "单次连接最大认证尝试次数"),
                    v("ClientAliveInterval", "300", "int", "服务端向客户端发送保活探测的间隔（秒）"),
                    v("ClientAliveCountMax", "2", "int", "保活探测无响应后断开连接的次数上限")
            )),

            new CategorySeed("timesync", "时间同步",
                    "系统时间同步上游（NTP）。本机为 systemd-timesyncd，"
                            + "写入 /etc/systemd/timesyncd.conf.d/99-serverpanel.conf（Chrony 则写入其 conf.d）。",
                    "/etc/systemd/timesyncd.conf.d/99-serverpanel.conf", "L1",
                    "生效会重启时间同步服务，可能有数秒同步空窗；容器共享宿主时钟。",
                    40, List.of(
                    v("NTP", "ntp.aliyun.com cn.pool.ntp.org", "string",
                            "首选 NTP 上游（空格分隔多个）"),
                    v("FallbackNTP", "ntp1.aliyun.com ntp2.aliyun.com", "string",
                            "NTP= 不可用时的回退上游"),
                    vInt("RootDistanceMaxSec", "5", "根距离上限（秒），超过则视为不可信源"),
                    vInt("PollIntervalMinSec", "32", "最小轮询间隔（秒）"),
                    vInt("PollIntervalMaxSec", "2048", "最大轮询间隔（秒）")
            ))
    );
}
