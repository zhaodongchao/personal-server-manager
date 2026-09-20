package com.serverpanel.monitor.service;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.monitor.dto.MetricFrame;
import com.serverpanel.monitor.dto.MonitorOverview;
import com.serverpanel.monitor.dto.NetworkInfo;
import com.serverpanel.monitor.entity.MonMetricHour;
import com.serverpanel.monitor.mapper.MonMetricHourMapper;
import com.serverpanel.monitor.ws.MonitorWebSocketHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 系统指标采集器（OSHI）。
 *
 * <p>每 5s 一帧：写入 Redis 环形缓存（保留 720 帧 = 1 小时），
 * 并经 WebSocket 广播给订阅者；每小时整点聚合上一小时入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollector {

    /** Redis 保留帧数（1 小时 / 5s） */
    private static final long FRAME_RETENTION = 720;

    /** 伪文件系统类型：无真实磁盘占用，文件系统中不展示（含其子树） */
    private static final Set<String> PSEUDO_FS = Set.of(
            "proc",
            "sysfs",
            "devtmpfs",
            "devpts",
            "cgroup",
            "cgroup2",
            "mqueue",
            "hugetlbfs",
            "securityfs",
            "debugfs",
            "pstore",
            "bpf",
            "configfs",
            "fusectl",
            "rpc_pipefs",
            "autofs",
            "tracefs",
            "efivarfs",
            "overlay",
            "aufs");

    /** 挂载表中跳过的虚拟设备源前缀（loop 镜像 / 内存盘 / 光驱等；dm 与真实磁盘保留） */
    private static final Set<String> MOUNT_SOURCE_SKIP = Set.of("loop", "ram", "zram", "sr", "fd", "nbd");

    /** 挂载表中跳过的挂载点前缀（容器运行时内部挂载，如 docker overlay2 / 容器 shm） */
    private static final List<String> MOUNT_TARGET_SKIP = List.of("/var/lib/docker/", "/var/lib/containers/");

    /** 非 LVM 的常见 dm 设备名前缀（dm-crypt / multipath / dmraid），名称推导卷组时排除 */
    private static final List<String> NON_LVM_DM_PREFIX =
            List.of("luks", "crypt", "mpath", "isw", "dmraid", "raid", "md-", "lvmcache");

    private final SystemInfo systemInfo = new SystemInfo();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorWebSocketHandler wsHandler;
    private final MonMetricHourMapper metricHourMapper;
    private final CommandExecutor commandExecutor;

    /**
     * 宿主机文件访问（容器部署时按宿主机视角读取 /proc 与 /sys）：
     * 磁盘 / 挂载表 / LVM 的 sysroot 逻辑统一委托给它，避免多处重复解析。
     */
    private final HostFileAccess hostFs;

    /** 网络信息采集器（主机网卡明细 + Docker 虚拟网络） */
    private final NetworkCollector networkCollector;

    /** 上一帧网络累计字节数与时间戳（速率差分） */
    private long prevNetIn;

    private long prevNetOut;
    private long prevTs;

    /** 上一帧 CPU ticks（使用率差分） */
    private long[] prevCpuTicks;

    private volatile MetricFrame latestFrame;

    /** 最新网络信息快照（主机网卡 + Docker 网络，与实时帧同频刷新） */
    private volatile NetworkInfo latestNetwork;

    /** 采集 + 缓存 + 广播 */
    @Scheduled(fixedDelayString = "PT${serverpanel.monitor.interval-seconds:5}S", initialDelayString = "PT2S")
    public void collect() {
        try {
            MetricFrame frame = snapshot();
            latestFrame = frame;

            // Redis 环形缓存（头部插入，保留最近 720 帧）
            String json = objectMapper.writeValueAsString(frame);
            redisTemplate.opsForList().leftPush(CacheConstants.MON_FRAME_RECENT, json);
            redisTemplate.opsForList().trim(CacheConstants.MON_FRAME_RECENT, 0, FRAME_RETENTION - 1);

            // 推送给在线订阅者
            wsHandler.broadcast(frame);
        } catch (Exception e) {
            log.error("Metrics collection failed: {}", e.getMessage());
        }

        // 网络信息与实时帧同频刷新；独立 try：网络采集失败不影响指标帧与推送
        try {
            latestNetwork = networkCollector.collect();
        } catch (Exception e) {
            log.error("Network collection failed: {}", e.getMessage());
        }
    }

    /** 采集一帧 */
    public MetricFrame snapshot() {
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        CentralProcessor processor = hardware.getProcessor();
        OperatingSystem os = systemInfo.getOperatingSystem();

        MetricFrame frame = new MetricFrame();
        long now = System.currentTimeMillis();
        frame.setTs(now);
        frame.setUptimeSeconds(os.getSystemUptime());

        // CPU（与上一帧 ticks 差分，无需 sleep）
        long[] cpuTicks = processor.getSystemCpuLoadTicks();
        if (prevCpuTicks != null) {
            double cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks);
            frame.setCpuUsage(round2((Double.isNaN(cpuLoad) ? 0 : cpuLoad) * 100));
        }
        prevCpuTicks = cpuTicks;

        // 内存
        GlobalMemory memory = hardware.getMemory();
        long memTotal = memory.getTotal();
        long memUsed = memTotal - memory.getAvailable();
        frame.setMemTotal(memTotal);
        frame.setMemUsed(memUsed);
        frame.setMemUsage(memTotal == 0 ? 0 : round2(memUsed * 100.0 / memTotal));

        // 负载（Linux 有效）
        double[] load = processor.getSystemLoadAverage(3);
        frame.setLoadAvg1(load[0] < 0 ? 0 : round2(load[0]));
        frame.setLoadAvg5(load[1] < 0 ? 0 : round2(load[1]));
        frame.setLoadAvg15(load[2] < 0 ? 0 : round2(load[2]));

        // 网络速率：非 loopback 接口求和后差分
        long netIn = 0;
        long netOut = 0;
        for (NetworkIF netif : hardware.getNetworkIFs()) {
            if (netif.getName().startsWith("lo")) {
                continue;
            }
            netIn += netif.getBytesRecv();
            netOut += netif.getBytesSent();
        }
        if (prevTs > 0 && now > prevTs) {
            double seconds = (now - prevTs) / 1000.0;
            frame.setNetInRate(round2((netIn - prevNetIn) / 1024.0 / seconds));
            frame.setNetOutRate(round2((netOut - prevNetOut) / 1024.0 / seconds));
        }
        prevNetIn = netIn;
        prevNetOut = netOut;
        prevTs = now;

        // 根分区
        File root = new File("/");
        long diskTotal = root.getTotalSpace();
        long diskUsable = root.getUsableSpace();
        long diskUsed = diskTotal - diskUsable;
        frame.setDiskTotal(diskTotal);
        frame.setDiskUsed(diskUsed);
        frame.setDiskUsage(diskTotal == 0 ? 0 : round2(diskUsed * 100.0 / diskTotal));
        return frame;
    }

    /** 最新一帧 */
    public MetricFrame latest() {
        MetricFrame frame = latestFrame;
        return frame == null ? snapshot() : frame;
    }

    /** 最新网络信息快照（主机网卡明细 + Docker 虚拟网络；尚未采集时立即采集一次） */
    public NetworkInfo network() {
        NetworkInfo info = latestNetwork;
        return info == null ? networkCollector.collect() : info;
    }

    /** 系统静态信息概览 */
    public MonitorOverview overview() {
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        OperatingSystem os = systemInfo.getOperatingSystem();
        CentralProcessor processor = hardware.getProcessor();

        MonitorOverview vo = new MonitorOverview();
        try {
            vo.setHostname(java.net.InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            vo.setHostname("localhost");
        }
        vo.setOs(os.getFamily() + " " + os.getVersionInfo());
        vo.setKernel(System.getProperty("os.version"));
        vo.setCpuModel(processor.getProcessorIdentifier().getName().trim());
        vo.setCpuPhysicalCores(processor.getPhysicalPackageCount());
        vo.setCpuLogicalCores(processor.getLogicalProcessorCount());

        // 挂载表（容器部署时读宿主机 <sysroot>/proc/mounts）与块设备拓扑（lsblk --sysroot）
        Map<String, MountEntry> mounts = loadMounts();
        LsblkData lsblk = loadLsblkData();
        // lsblk 未产出任何物理磁盘/Device Mapper（如权限异常或全部为虚拟设备）时回退 OSHI 硬件扫描
        if (lsblk.physicalDisks.isEmpty() && lsblk.deviceMappers.isEmpty()) {
            fillFromOshi(lsblk, hardware);
        }
        List<MonitorOverview.DiskInfo> disks = collectDisks(mounts, lsblk.dmAlias);
        if (disks.isEmpty()) {
            disks = collectDisksFromOshi();
        }
        vo.setDisks(disks);
        vo.setPhysicalDisks(lsblk.physicalDisks);
        vo.setDeviceMappers(lsblk.deviceMappers);
        vo.setLvm(collectLvm(lsblk));
        // 将挂载点回填到分区 / Device Mapper / 逻辑卷，并补全容器内读不到的文件系统类型
        applyMounts(vo, mounts, lsblk.dmAlias);

        // 网络明细复用采集器快照（与 /monitor/network 同构），避免每次概览都全量扫网卡
        NetworkInfo network = network();
        vo.setInterfaces(network.getInterfaces());
        vo.setDockerNetworks(network.getDockerNetworks());
        vo.setNetworkSummary(network.getSummary());

        vo.setLatest(latest());
        return vo;
    }

    /** 最近 N 分钟历史帧（时间升序） */
    public List<MetricFrame> history(Duration duration) {
        List<String> raw = redisTemplate.opsForList().range(CacheConstants.MON_FRAME_RECENT, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        long fromTs = System.currentTimeMillis() - duration.toMillis();
        List<MetricFrame> result = new ArrayList<>();
        for (String item : raw) {
            try {
                MetricFrame frame = objectMapper.readValue(item, MetricFrame.class);
                if (frame.getTs() >= fromTs) {
                    result.add(frame);
                }
            } catch (Exception ignored) {
                // 跳过坏帧
            }
        }
        return result;
    }

    /** 每小时整点：聚合上一小时数据入库（幂等 upsert） */
    @Scheduled(cron = "0 0 * * * *")
    public void hourlyAggregate() {
        try {
            LocalDateTime hourEnd = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            LocalDateTime hourStart = hourEnd.minusHours(1);
            long from = hourStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long to = hourEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            List<MetricFrame> frames = history(Duration.ofHours(1)).stream()
                    .filter(f -> f.getTs() >= from && f.getTs() < to)
                    .toList();
            if (frames.isEmpty()) {
                return;
            }
            int n = frames.size();
            double cpu = frames.stream()
                    .mapToDouble(MetricFrame::getCpuUsage)
                    .average()
                    .orElse(0);
            double mem = frames.stream()
                    .mapToDouble(MetricFrame::getMemUsage)
                    .average()
                    .orElse(0);
            double disk = frames.stream()
                    .mapToDouble(MetricFrame::getDiskUsage)
                    .average()
                    .orElse(0);
            double load = frames.stream()
                    .mapToDouble(MetricFrame::getLoadAvg1)
                    .average()
                    .orElse(0);
            double netInMb =
                    frames.stream().mapToDouble(MetricFrame::getNetInRate).sum() * 5 / 1024;
            double netOutMb =
                    frames.stream().mapToDouble(MetricFrame::getNetOutRate).sum() * 5 / 1024;

            MonMetricHour agg = metricHourMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MonMetricHour>()
                            .eq(MonMetricHour::getMetricTime, hourStart));
            boolean exists = agg != null;
            if (agg == null) {
                agg = new MonMetricHour();
                agg.setMetricTime(hourStart);
            }
            agg.setCpuUsage(BigDecimal.valueOf(cpu).setScale(2, RoundingMode.HALF_UP));
            agg.setMemUsage(BigDecimal.valueOf(mem).setScale(2, RoundingMode.HALF_UP));
            agg.setDiskUsage(BigDecimal.valueOf(disk).setScale(2, RoundingMode.HALF_UP));
            agg.setNetInMb(BigDecimal.valueOf(netInMb).setScale(2, RoundingMode.HALF_UP));
            agg.setNetOutMb(BigDecimal.valueOf(netOutMb).setScale(2, RoundingMode.HALF_UP));
            agg.setLoadAvg(BigDecimal.valueOf(load).setScale(2, RoundingMode.HALF_UP));
            if (exists) {
                metricHourMapper.updateById(agg);
            } else {
                metricHourMapper.insert(agg);
            }
            log.debug("Hourly metrics aggregated for {} with {} frames", hourStart, n);
        } catch (Exception e) {
            log.error("Hourly aggregate failed: {}", e.getMessage());
        }
    }

    /** 挂载表条目（源自 <sysroot>/proc/mounts，容器部署时为宿主机真实挂载） */
    private record MountEntry(String source, String target, String fstype) {}

    /**
     * 解析挂载表：直读 <sysroot>/proc/mounts（容器部署时读宿主机挂载表，替代容器内 findmnt）。
     * 跳过伪文件系统 / 虚拟设备源 / 容器运行时内部挂载；同一挂载点后挂载覆盖先挂载。
     *
     * @return target → 挂载条目（保持文件内顺序）
     */
    private Map<String, MountEntry> loadMounts() {
        Map<String, MountEntry> byTarget = new LinkedHashMap<>();
        String content = readHostFile("/proc/mounts");
        if (content.isBlank()) {
            log.warn("读取挂载表失败（{}proc/mounts），文件系统列表将回退 OSHI", sysroot());
            return byTarget;
        }
        for (String line : content.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            String source = unescapeMount(fields[0]);
            String target = unescapeMount(fields[1]);
            String fstype = fields[2];
            if (isPseudoFs(fstype) || !source.startsWith("/dev/")) {
                continue;
            }
            String base = source.substring(source.lastIndexOf('/') + 1);
            if (base.isEmpty() || MOUNT_SOURCE_SKIP.stream().anyMatch(base::startsWith)) {
                continue;
            }
            if (MOUNT_TARGET_SKIP.stream().anyMatch(target::startsWith)) {
                continue;
            }
            byTarget.put(target, new MountEntry(source, target, fstype));
        }
        return byTarget;
    }

    /** 还原 /proc/mounts 中的八进制转义（\040 空格 / \011 制表符 / \134 反斜杠） */
    private String unescapeMount(String s) {
        return s.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\");
    }

    /** 是否伪文件系统（无真实磁盘占用），此类挂载点不展示 */
    private boolean isPseudoFs(String fsType) {
        return fsType == null || PSEUDO_FS.contains(fsType);
    }

    /** 宿主机根前缀（委托 HostFileAccess，解析结果由其实例缓存） */
    private String sysroot() {
        return hostFs.sysroot();
    }

    /** 读取宿主机文件（委托 HostFileAccess：优先 sysroot 前缀路径，失败回退本机） */
    private String readHostFile(String relPath) {
        return hostFs.readFile(relPath);
    }

    /** 由挂载表构建文件系统列表：挂载点 / 源设备 / 文件系统类型 / 所属 LVM 卷组 / 容量与使用率 */
    private List<MonitorOverview.DiskInfo> collectDisks(Map<String, MountEntry> mounts, Map<String, String> dmAlias) {
        List<MonitorOverview.DiskInfo> list = new ArrayList<>();
        String sr = sysroot();
        for (MountEntry m : mounts.values()) {
            MonitorOverview.DiskInfo disk = new MonitorOverview.DiskInfo();
            disk.setMount(m.target());
            disk.setSource(m.source());
            disk.setFsType(m.fstype());
            disk.setVg(vgOfMountSource(m, dmAlias));
            // 容器部署时通过 <sysroot>/<target> 对宿主机文件系统做 statfs
            File file = new File(sr.isEmpty() ? m.target() : sr + m.target());
            long total = file.getTotalSpace();
            long usable = file.getUsableSpace();
            disk.setTotalBytes(total);
            disk.setUsableBytes(usable);
            disk.setUsage(total <= 0 ? 0 : round2((total - usable) * 100.0 / total));
            list.add(disk);
        }
        return list;
    }

    /** 挂载源对应的 LVM 卷组：/dev/mapper/vg0-root → vg0；dm-N 经 sysfs 别名解析；非 LVM 返回空串 */
    private String vgOfMountSource(MountEntry m, Map<String, String> dmAlias) {
        String base = m.source().substring(m.source().lastIndexOf('/') + 1);
        if (!m.source().startsWith("/dev/mapper/")) {
            String alias = dmAlias.get(base);
            if (alias == null) {
                return "";
            }
            base = alias;
        }
        if (NON_LVM_DM_PREFIX.stream().anyMatch(base::startsWith)) {
            return "";
        }
        String[] vgLv = splitVgLv(base);
        return vgLv == null ? "" : vgLv[0];
    }

    /** 回退：OSHI 文件系统快照 */
    private List<MonitorOverview.DiskInfo> collectDisksFromOshi() {
        List<MonitorOverview.DiskInfo> list = new ArrayList<>();
        for (OSFileStore store : systemInfo.getOperatingSystem().getFileSystem().getFileStores(true)) {
            MonitorOverview.DiskInfo disk = new MonitorOverview.DiskInfo();
            disk.setMount(store.getMount());
            disk.setFsType(store.getType());
            long total = store.getTotalSpace();
            disk.setTotalBytes(total);
            disk.setUsableBytes(store.getUsableSpace());
            disk.setUsage(total == 0 ? 0 : round2((total - store.getUsableSpace()) * 100.0 / total));
            list.add(disk);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 单次 lsblk 扫描结果：物理磁盘 / Device Mapper / LVM 拓扑回退数据 */
    private static class LsblkData {
        final List<MonitorOverview.PhysicalDisk> physicalDisks = new ArrayList<>();
        final List<MonitorOverview.DeviceMapper> deviceMappers = new ArrayList<>();
        final List<MonitorOverview.PhysicalVolume> physicalVolumes = new ArrayList<>();
        final List<MonitorOverview.VolumeGroup> volumeGroups = new ArrayList<>();
        final List<MonitorOverview.LogicalVolume> logicalVolumes = new ArrayList<>();
        /** VG 聚合临时态（名称 → 统计），遍历结束后转为 volumeGroups */
        final Map<String, VgAgg> vgAgg = new LinkedHashMap<>();
        /** dm 设备内核名 → 真实映射名（dm-0 → vg0-root），用于挂载源别名匹配 */
        final Map<String, String> dmAlias = new LinkedHashMap<>();
    }

    /** VG 聚合统计（由 lsblk LVM 拓扑推导，用于 pvs/vgs/lvs 不可用时的回退） */
    private static class VgAgg {
        long size;
        long lvSize;
        int pvCount;
        int lvCount;
    }

    /** 非真实磁盘的设备名前缀：loop/dm/ram/sr 等虚拟或只读设备不作为物理磁盘展示 */
    private static final Set<String> PHONY_DISK_PREFIX = Set.of("loop", "nbd", "ram", "sr", "fd", "dm", "zram");

    /** lsblk 输出列（KNAME 用于容器内解析 dm 设备真名） */
    private static final String LSBLK_COLUMNS = "NAME,KNAME,SIZE,TYPE,FSTYPE,MODEL,SERIAL";

    /**
     * 通过 lsblk（JSON）一次性采集物理磁盘、Device Mapper 与 LVM 拓扑数据。
     * 容器部署时加 {@code --sysroot <host>} 读取宿主机块设备（宿主机 / 需挂载进容器）。
     */
    private LsblkData loadLsblkData() {
        LsblkData data = new LsblkData();
        try {
            // lsblk 读取 /sys，无需 root；sysfs 不受容器隔离，容器内也可读到宿主机块设备拓扑
            ExecResult result;
            String sr = sysroot();
            if (sr.isEmpty()) {
                result = commandExecutor.exec("lsblk", "-J", "-b", "-o", LSBLK_COLUMNS);
            } else {
                result = commandExecutor.exec("lsblk", "-J", "-b", "-o", LSBLK_COLUMNS, "--sysroot", sr);
                if (!result.isSuccess()) {
                    // lsblk 版本过旧不支持 --sysroot 等：回退普通执行（容器内为容器视角，尽力而为）
                    log.warn("lsblk --sysroot {} 失败，回退普通 lsblk（输出可能为容器视角）", sr);
                    result = commandExecutor.exec("lsblk", "-J", "-b", "-o", LSBLK_COLUMNS);
                }
            }
            if (!result.isSuccess()
                    || result.getStdout() == null
                    || result.getStdout().isBlank()) {
                return data;
            }
            JsonNode root = objectMapper.readTree(result.getStdout());
            for (JsonNode node : root.path("blockdevices")) {
                collectLsblkNode(node, data);
            }
        } catch (Exception e) {
            log.warn("lsblk 采集失败（物理磁盘/Device Mapper/LVM 回退置空）：{}", e.getMessage());
        }
        return data;
    }

    /** 递归遍历 lsblk 树节点，分别归类物理磁盘、Device Mapper 与 LVM 拓扑 */
    private void collectLsblkNode(JsonNode node, LsblkData data) {
        String type = node.path("type").asText("");
        if ("lvm".equals(type) || "dm".equals(type)) {
            // dm/lvm 节点统一处理：容器内无 udev 时 type 只有 dm，需从 sysfs 判断是否 LVM
            addDmNode(node, data);
        } else if ("disk".equals(type)) {
            // 整盘若挂有 dm/lvm 子节点即为物理卷 PV
            registerPvIfDmChild(node, data);
            if (isRealDisk(node.path("name").asText(""))) {
                data.physicalDisks.add(buildPhysicalDisk(node));
            }
        } else if ("part".equals(type)) {
            // 分区可能是物理卷（PV），其 children 挂有 dm/lvm 的逻辑卷
            registerPvIfDmChild(node, data);
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                collectLsblkNode(child, data);
            }
        }
    }

    /**
     * 解析并登记 dm/lvm 节点：一律作为 Device Mapper 设备；
     * 确属 LVM 逻辑卷时同时登记 LV 拓扑并累计 VG 容量。
     */
    private void addDmNode(JsonNode node, LsblkData data) {
        String realName = dmRealName(node); // 容器内 name 可能退化为 dm-N，从 sysfs 解析真名
        String kname = node.path("kname").asText(node.path("name").asText(""));
        if (!kname.isEmpty() && !kname.equals(realName)) {
            data.dmAlias.put(kname, realName);
        }
        String vg = isLvmDm(node, realName) ? dmVg(realName) : "";
        long size = parseLongVal(node.path("size").asText());
        String fstype = node.path("fstype").asText("");

        MonitorOverview.DeviceMapper mapper = new MonitorOverview.DeviceMapper();
        mapper.setName("/dev/mapper/" + realName);
        mapper.setVg(vg);
        mapper.setSizeBytes(size);
        mapper.setFsType(fstype);
        mapper.setMount("");
        data.deviceMappers.add(mapper);

        if (!vg.isEmpty()) {
            MonitorOverview.LogicalVolume lv = new MonitorOverview.LogicalVolume();
            lv.setName(realName);
            lv.setVg(vg);
            lv.setSizeBytes(size);
            lv.setFsType(fstype);
            lv.setMount("");
            data.logicalVolumes.add(lv);

            VgAgg agg = data.vgAgg.computeIfAbsent(vg, k -> new VgAgg());
            agg.lvSize += size;
            agg.lvCount++;
        }
    }

    /** 解析 dm 节点的真实设备名：name 为 dm-N（容器内无 udev）时读 sysfs dm/name */
    private String dmRealName(JsonNode node) {
        String name = node.path("name").asText("");
        if (name.matches("dm-\\d+")) {
            String kname = node.path("kname").asText(name);
            String sysfsName = readBlockSysfs(kname, "dm", "name");
            if (!sysfsName.isEmpty()) {
                return sysfsName;
            }
        }
        return name;
    }

    /**
     * 判断 dm 节点是否为 LVM 逻辑卷：type=lvm，或 sysfs dm/uuid 以 LVM- 开头
     * （容器内 lsblk 无 udev 数据库时的可靠依据）；均不可用时按名称排除法推断。
     */
    private boolean isLvmDm(JsonNode node, String realName) {
        if ("lvm".equals(node.path("type").asText(""))) {
            return true;
        }
        if (realName.matches("dm-\\d+")) {
            return false; // 未解析出真名的裸内核设备名，无法判定为 LVM
        }
        String kname = node.path("kname").asText(node.path("name").asText(""));
        String uuid = readBlockSysfs(kname, "dm", "uuid");
        if (uuid.startsWith("LVM-")) {
            return true;
        }
        // 兜底：排除常见非 LVM dm 前缀后，形如 vg-lv 的映射名视为 LVM
        if (NON_LVM_DM_PREFIX.stream().anyMatch(realName::startsWith)) {
            return false;
        }
        return splitVgLv(realName) != null;
    }

    /** 若整盘或分区挂有 dm/lvm 子节点（即 LVM 物理卷 PV），登记 PV 并累计所属 VG 容量 */
    private void registerPvIfDmChild(JsonNode node, LsblkData data) {
        JsonNode children = node.path("children");
        if (!children.isArray()) {
            return;
        }
        String vg = "";
        for (JsonNode child : children) {
            String childType = child.path("type").asText("");
            if ("lvm".equals(childType) || "dm".equals(childType)) {
                String realName = dmRealName(child);
                if (isLvmDm(child, realName)) {
                    vg = dmVg(realName);
                    if (!vg.isEmpty()) {
                        break;
                    }
                }
            }
        }
        if (vg.isEmpty()) {
            return;
        }
        MonitorOverview.PhysicalVolume pv = new MonitorOverview.PhysicalVolume();
        pv.setName("/dev/" + node.path("name").asText());
        pv.setVg(vg);
        pv.setSizeBytes(parseLongVal(node.path("size").asText()));
        pv.setFreeBytes(0);
        data.physicalVolumes.add(pv);

        VgAgg agg = data.vgAgg.computeIfAbsent(vg, k -> new VgAgg());
        agg.size += pv.getSizeBytes();
        agg.pvCount++;
    }

    /** LVM 设备映射名（vg0-root）推导卷组名；名称不合法返回空串 */
    private String dmVg(String dmName) {
        String[] vgLv = splitVgLv(dmName);
        return vgLv == null ? "" : vgLv[0];
    }

    /**
     * 拆分 LVM 设备映射名为 [vg, lv]。LVM 规则：名称中的连字符转义为 {@code --}，
     * 卷组与逻辑卷之间以单个 {@code -} 分隔（如 my--vg-root → [my-vg, root]）。
     * 非 vg-lv 形式（无分隔符）返回 null。
     */
    private String[] splitVgLv(String dmName) {
        if (dmName == null || dmName.isEmpty()) {
            return null;
        }
        StringBuilder vg = new StringBuilder();
        int i = 0;
        while (i < dmName.length()) {
            char c = dmName.charAt(i);
            if (c == '-') {
                if (i + 1 < dmName.length() && dmName.charAt(i + 1) == '-') {
                    vg.append('-'); // 转义的连字符
                    i += 2;
                } else {
                    break; // vg 与 lv 的分隔符
                }
            } else {
                vg.append(c);
                i++;
            }
        }
        // 分隔符必须存在，且 vg / lv 均非空
        if (i >= dmName.length() || vg.length() == 0) {
            return null;
        }
        String lv = dmName.substring(i + 1).replace("--", "-");
        return lv.isEmpty() ? null : new String[] {vg.toString(), lv};
    }

    /** 是否为真实物理磁盘（排除 loop/dm/ram 等虚拟设备；兼容有/无 /dev/ 前缀） */
    private boolean isRealDisk(String name) {
        if (name == null) {
            return false;
        }
        String base = name;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.isEmpty()) {
            return false;
        }
        for (String prefix : PHONY_DISK_PREFIX) {
            if (base.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** 校验/归一化 lsblk 的 model/serial，避免 null */
    private String nullToEmpty(String s) {
        return s == null || "n/a".equals(s) ? "" : s;
    }

    /** 尝试读取块设备 sysfs 属性（<sysroot>/sys/block/<dev>/<sub>/<attr>），失败返回空串 */
    private String readBlockSysfs(String devName, String sub, String attr) {
        String base = devName.substring(devName.lastIndexOf('/') + 1);
        return readHostFile("/sys/block/" + base + "/" + sub + "/" + attr).trim();
    }

    /** 由 lsblk 节点构建物理磁盘（仅含真实磁盘及其分区；分区带文件系统类型与所属 VG） */
    private MonitorOverview.PhysicalDisk buildPhysicalDisk(JsonNode node) {
        MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
        String devName = node.path("name").asText("");
        disk.setName("/dev/" + devName);
        // 优先取 lsblk 的 MODEL/SERIAL，缺失时读取 sysfs 属性（容器内 lsblk 可能读不到）
        String model = nullToEmpty(node.path("model").asText());
        String serial = nullToEmpty(node.path("serial").asText());
        if (model.isEmpty()) {
            model = readBlockSysfs(devName, "device", "model");
        }
        if (serial.isEmpty()) {
            serial = readBlockSysfs(devName, "device", "serial");
        }
        disk.setModel(model);
        disk.setSerial(serial);
        disk.setSizeBytes(parseLongVal(node.path("size").asText()));
        List<MonitorOverview.Partition> parts = new ArrayList<>();
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                if (!"part".equals(child.path("type").asText(""))) {
                    continue;
                }
                MonitorOverview.Partition p = new MonitorOverview.Partition();
                p.setName("/dev/" + child.path("name").asText());
                p.setSizeBytes(parseLongVal(child.path("size").asText()));
                p.setType(child.path("fstype").asText(""));
                // 分区作为 LVM 物理卷时，从其 dm 子节点推导所属卷组
                p.setVg(childDmVg(child));
                p.setMount("");
                parts.add(p);
            }
        }
        disk.setPartitions(parts.isEmpty() ? List.of() : parts);
        return disk;
    }

    /** 取节点下第一个 LVM dm 子节点对应的卷组名（非 PV 返回空串） */
    private String childDmVg(JsonNode node) {
        JsonNode children = node.path("children");
        if (!children.isArray()) {
            return "";
        }
        for (JsonNode child : children) {
            String childType = child.path("type").asText("");
            if ("lvm".equals(childType) || "dm".equals(childType)) {
                String realName = dmRealName(child);
                if (isLvmDm(child, realName)) {
                    return dmVg(realName);
                }
            }
        }
        return "";
    }

    /** 回退：OSHI 硬件扫描，将磁盘分为真实物理磁盘与 Device Mapper（排除 loop/ram 等虚拟设备） */
    private void fillFromOshi(LsblkData data, HardwareAbstractionLayer hardware) {
        for (HWDiskStore store : hardware.getDiskStores()) {
            String name = store.getName(); // 形如 /dev/sda 或 /dev/dm-0
            String base = name;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
            // 虚拟设备：dm 归入 Device Mapper 集合，loop/ram/nbd 等直接忽略
            if (!isRealDisk(base)) {
                if (base.startsWith("dm")) {
                    data.deviceMappers.add(buildMapperFromOshi(store));
                }
                continue;
            }
            data.physicalDisks.add(buildPhysicalDiskFromOshi(store));
        }
    }

    /** 由 OSHI 构建 Device Mapper 设备（OSHI 不提供文件系统类型/挂载点，置空） */
    private MonitorOverview.DeviceMapper buildMapperFromOshi(HWDiskStore store) {
        MonitorOverview.DeviceMapper mapper = new MonitorOverview.DeviceMapper();
        mapper.setName(store.getName());
        mapper.setSizeBytes(store.getSize());
        mapper.setFsType("");
        mapper.setMount("");
        return mapper;
    }

    /** 由 OSHI 构建真实物理磁盘（型号/序列号缺失时尝试 sysfs 读取） */
    private MonitorOverview.PhysicalDisk buildPhysicalDiskFromOshi(HWDiskStore store) {
        MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
        disk.setName(store.getName());
        String model = nullToEmpty(store.getModel());
        String serial = nullToEmpty(store.getSerial());
        if (model.isEmpty()) {
            model = readBlockSysfs(store.getName(), "device", "model");
        }
        if (serial.isEmpty()) {
            serial = readBlockSysfs(store.getName(), "device", "serial");
        }
        disk.setModel(model);
        disk.setSerial(serial);
        disk.setSizeBytes(store.getSize());
        List<MonitorOverview.Partition> parts = new ArrayList<>();
        for (HWPartition part : store.getPartitions()) {
            MonitorOverview.Partition p = new MonitorOverview.Partition();
            p.setName(part.getIdentification());
            p.setMount(part.getMountPoint() == null ? "" : part.getMountPoint());
            p.setSizeBytes(part.getSize());
            p.setType(part.getType());
            p.setVg("");
            parts.add(p);
        }
        disk.setPartitions(parts.isEmpty() ? List.of() : parts);
        return disk;
    }

    /**
     * 将挂载表回填到分区 / Device Mapper / 逻辑卷：按挂载源设备名匹配
     * （含 dm-0 内核名 → vg0-root 映射名别名），并补全容器内读不到的文件系统类型。
     */
    private void applyMounts(MonitorOverview vo, Map<String, MountEntry> mounts, Map<String, String> dmAlias) {
        if (mounts.isEmpty()) {
            return;
        }
        // 挂载源 basename → 挂载条目（同一源多处挂载取第一处）
        Map<String, MountEntry> bySource = new LinkedHashMap<>();
        for (MountEntry m : mounts.values()) {
            String base = m.source().substring(m.source().lastIndexOf('/') + 1);
            bySource.putIfAbsent(base, m);
        }
        if (vo.getPhysicalDisks() != null) {
            for (MonitorOverview.PhysicalDisk disk : vo.getPhysicalDisks()) {
                for (MonitorOverview.Partition p : disk.getPartitions()) {
                    applyMount(p.getName(), bySource, dmAlias, p::setMount, p::setType);
                }
            }
        }
        if (vo.getDeviceMappers() != null) {
            for (MonitorOverview.DeviceMapper dm : vo.getDeviceMappers()) {
                applyMount(dm.getName(), bySource, dmAlias, dm::setMount, dm::setFsType);
            }
        }
        if (vo.getLvm() != null && vo.getLvm().getLogicalVolumes() != null) {
            for (MonitorOverview.LogicalVolume lv : vo.getLvm().getLogicalVolumes()) {
                applyMount(lv.getName(), bySource, dmAlias, lv::setMount, lv::setFsType);
            }
        }
    }

    /** 按设备名（含 dm 内核名别名）匹配挂载条目，命中则回填挂载点与缺失的文件系统类型 */
    private void applyMount(
            String deviceName,
            Map<String, MountEntry> bySource,
            Map<String, String> dmAlias,
            java.util.function.Consumer<String> mountSetter,
            java.util.function.Consumer<String> fsTypeSetter) {
        if (deviceName == null || deviceName.isEmpty()) {
            return;
        }
        String base = deviceName.substring(deviceName.lastIndexOf('/') + 1);
        MountEntry m = bySource.get(base);
        if (m == null) {
            String alias = dmAlias.get(base);
            if (alias != null) {
                m = bySource.get(alias);
            }
        }
        if (m != null) {
            mountSetter.accept(m.target());
            // 容器内 lsblk 常读不到超级块（fstype 为空），用挂载表补全
            fsTypeSetter.accept(m.fstype());
        }
    }

    /** 采集 LVM 信息：优先 pvs/vgs/lvs（含 PE/空间明细），工具或 sudo 不可用时回退 lsblk 拓扑 */
    private MonitorOverview.LvmInfo collectLvm(LsblkData lsblk) {
        MonitorOverview.LvmInfo lvm = new MonitorOverview.LvmInfo();
        List<MonitorOverview.PhysicalVolume> pvs = parsePvs();
        List<MonitorOverview.VolumeGroup> vgs = parseVgs();
        List<MonitorOverview.LogicalVolume> lvs = parseLvs();
        // lvm2 工具/免密 sudo 不可用导致报表为空时，用 lsblk 拓扑兜底，保证页面有数据
        if (pvs.isEmpty() || vgs.isEmpty() || lvs.isEmpty()) {
            pvs = pvs.isEmpty() ? lsblk.physicalVolumes : pvs;
            vgs = vgs.isEmpty() ? deriveVolumeGroups(lsblk) : vgs;
            lvs = lvs.isEmpty() ? lsblk.logicalVolumes : lvs;
        }
        lvm.setPhysicalVolumes(pvs);
        lvm.setVolumeGroups(vgs);
        lvm.setLogicalVolumes(lvs);
        return lvm;
    }

    /** 由遍历期间聚合的 lsblk LVM 拓扑生成卷组列表（回退用） */
    private List<MonitorOverview.VolumeGroup> deriveVolumeGroups(LsblkData lsblk) {
        List<MonitorOverview.VolumeGroup> list = new ArrayList<>();
        for (Map.Entry<String, VgAgg> entry : lsblk.vgAgg.entrySet()) {
            VgAgg agg = entry.getValue();
            if (agg.lvCount == 0 && agg.pvCount == 0) {
                continue;
            }
            MonitorOverview.VolumeGroup vg = new MonitorOverview.VolumeGroup();
            vg.setName(entry.getKey());
            vg.setPvCount(agg.pvCount);
            vg.setLvCount(agg.lvCount);
            vg.setSizeBytes(agg.size);
            vg.setFreeBytes(Math.max(0, agg.size - agg.lvSize));
            list.add(vg);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** sudo 执行 LVM 只读报表命令并解析为 JSON 行；失败按空处理 */
    private List<JsonNode> runLvmJson(String cmd, String columns) {
        try {
            ExecResult result = commandExecutor.execSudo(
                    cmd, "--reportformat", "json", "--units", "b", "--nosuffix", "-o", columns);
            if (!result.isSuccess()
                    || result.getStdout() == null
                    || result.getStdout().isBlank()) {
                return List.of();
            }
            List<JsonNode> rows = new ArrayList<>();
            JsonNode report = objectMapper.readTree(result.getStdout()).path("report");
            if (report.isArray() && report.size() > 0) {
                String section = cmd.endsWith("s") ? cmd.substring(0, cmd.length() - 1) : cmd;
                JsonNode items = report.get(0).path(section);
                if (items.isArray()) {
                    items.forEach(rows::add);
                }
            }
            return rows.isEmpty() ? List.of() : rows;
        } catch (Exception e) {
            log.warn("LVM 查询 {} 失败：{}", cmd, e.getMessage());
            return List.of();
        }
    }

    /** 解析 PV 物理卷（路径 / 卷组 / 大小 / 可用） */
    private List<MonitorOverview.PhysicalVolume> parsePvs() {
        List<MonitorOverview.PhysicalVolume> list = new ArrayList<>();
        for (JsonNode r : runLvmJson("pvs", "pv_name,vg_name,pv_size,pv_free")) {
            MonitorOverview.PhysicalVolume pv = new MonitorOverview.PhysicalVolume();
            pv.setName(r.path("pv_name").asText());
            pv.setVg(r.path("vg_name").asText());
            pv.setSizeBytes(parseLongVal(r.path("pv_size").asText()));
            pv.setFreeBytes(parseLongVal(r.path("pv_free").asText()));
            list.add(pv);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 解析 VG 卷组（名称 / PV数 / LV数 / 大小 / 可用） */
    private List<MonitorOverview.VolumeGroup> parseVgs() {
        List<MonitorOverview.VolumeGroup> list = new ArrayList<>();
        for (JsonNode r : runLvmJson("vgs", "vg_name,pv_count,lv_count,vg_size,vg_free")) {
            MonitorOverview.VolumeGroup vg = new MonitorOverview.VolumeGroup();
            vg.setName(r.path("vg_name").asText());
            vg.setPvCount(parseIntVal(r.path("pv_count").asText()));
            vg.setLvCount(parseIntVal(r.path("lv_count").asText()));
            vg.setSizeBytes(parseLongVal(r.path("vg_size").asText()));
            vg.setFreeBytes(parseLongVal(r.path("vg_free").asText()));
            list.add(vg);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 解析 LV 逻辑卷（名称统一为设备映射名 vg-lv / 卷组 / 大小） */
    private List<MonitorOverview.LogicalVolume> parseLvs() {
        List<MonitorOverview.LogicalVolume> list = new ArrayList<>();
        for (JsonNode r : runLvmJson("lvs", "lv_name,vg_name,lv_size")) {
            String vg = r.path("vg_name").asText();
            String lvName = r.path("lv_name").asText();
            MonitorOverview.LogicalVolume lv = new MonitorOverview.LogicalVolume();
            // 组合为设备映射名（vg0-root），与挂载源 /dev/mapper/vg0-root 对齐；连字符按 LVM 规则转义
            lv.setName(escapeLvmName(vg) + "-" + escapeLvmName(lvName));
            lv.setVg(vg);
            lv.setSizeBytes(parseLongVal(r.path("lv_size").asText()));
            lv.setFsType("");
            lv.setMount("");
            list.add(lv);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** LVM 名称转义：卷组/逻辑卷名中的连字符在设备映射名中写作 -- */
    private String escapeLvmName(String s) {
        return s == null ? "" : s.replace("-", "--");
    }

    /** 安全解析 lvm 输出的字节数（可能含小数），失败归 0 */
    private long parseLongVal(String s) {
        try {
            String t = s.trim();
            if (t.isEmpty()) {
                return 0;
            }
            return (long) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 安全解析 lvm 输出的整数计数，失败归 0 */
    private int parseIntVal(String s) {
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
