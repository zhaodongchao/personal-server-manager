package com.serverpanel.monitor.service;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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

    /** 伪文件系统类型：无真实磁盘占用，文件系统 tree 中不展示（含其子树） */
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
            "efivarfs");

    private final SystemInfo systemInfo = new SystemInfo();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorWebSocketHandler wsHandler;
    private final MonMetricHourMapper metricHourMapper;
    private final CommandExecutor commandExecutor;

    /** 上一帧网络累计字节数与时间戳（速率差分） */
    private long prevNetIn;

    private long prevNetOut;
    private long prevTs;

    /** 上一帧 CPU ticks（使用率差分） */
    private long[] prevCpuTicks;

    private volatile MetricFrame latestFrame;

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

        vo.setDisks(collectDisks());
        LsblkData lsblk = loadLsblkData();
        // lsblk 未产出任何物理磁盘/Device Mapper（如权限异常或全部为虚拟设备）时回退 OSHI 硬件扫描
        if (lsblk.physicalDisks.isEmpty() && lsblk.deviceMappers.isEmpty()) {
            fillFromOshi(lsblk, hardware);
        }
        vo.setPhysicalDisks(lsblk.physicalDisks);
        vo.setDeviceMappers(lsblk.deviceMappers);
        vo.setLvm(collectLvm(lsblk));

        List<MonitorOverview.NetInterface> interfaces = new ArrayList<>();
        for (NetworkIF netif : hardware.getNetworkIFs()) {
            if (netif.getName().startsWith("lo")) {
                continue;
            }
            MonitorOverview.NetInterface item = new MonitorOverview.NetInterface();
            item.setName(netif.getName());
            item.setSpeed(netif.getSpeed());
            String[] ipv4 = netif.getIPv4addr();
            item.setIpv4(ipv4.length > 0 ? ipv4[0] : "");
            interfaces.add(item);
        }
        vo.setInterfaces(interfaces);

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

    /** 采集挂载的文件系统列表：优先 sudo findmnt（JSON），失败回退 OSHI */
    private List<MonitorOverview.DiskInfo> collectDisks() {
        List<MonitorOverview.DiskInfo> fromFindmnt = collectDisksFromFindmnt();
        return fromFindmnt.isEmpty() ? collectDisksFromOshi() : fromFindmnt;
    }

    /** 通过 sudo findmnt 采集所有真实文件系统挂载点（含树状层级），跳过伪文件系统 */
    private List<MonitorOverview.DiskInfo> collectDisksFromFindmnt() {
        try {
            // findmnt 读取 /proc/mounts，无需 root；不依赖 sudo，避免无免密 sudo 环境下采集为空
            ExecResult result = commandExecutor.exec("findmnt", "-J", "-b", "-o", "TARGET,FSTYPE,SIZE,AVAIL");
            if (!result.isSuccess()
                    || result.getStdout() == null
                    || result.getStdout().isBlank()) {
                return List.of();
            }
            List<MonitorOverview.DiskInfo> list = new ArrayList<>();
            walkFsTree(objectMapper.readTree(result.getStdout()).path("filesystems"), list);
            return list.isEmpty() ? List.of() : list;
        } catch (Exception e) {
            log.warn("findmnt 采集挂载点失败，回退 OSHI：{}", e.getMessage());
            return List.of();
        }
    }

    /** 递归遍历 findmnt JSON 树，聚合所有有挂载点的文件系统，跳过伪文件系统及其子树 */
    private void walkFsTree(JsonNode node, List<MonitorOverview.DiskInfo> out) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return;
        }
        String fsType = node.path("fstype").asText("");
        if (isPseudoFs(fsType)) {
            return;
        }
        MonitorOverview.DiskInfo disk = new MonitorOverview.DiskInfo();
        disk.setMount(node.path("target").asText(""));
        disk.setFsType(fsType);
        long total = parseLongVal(node.path("size").asText());
        long avail = parseLongVal(node.path("avail").asText());
        disk.setTotalBytes(total);
        disk.setUsableBytes(avail);
        disk.setUsage(total <= 0 ? 0 : round2((total - avail) * 100.0 / total));
        out.add(disk);
        for (JsonNode child : node.path("children")) {
            walkFsTree(child, out);
        }
    }

    /** 是否伪文件系统（无真实磁盘占用），此类挂载点不展示 */
    private boolean isPseudoFs(String fsType) {
        return fsType == null || PSEUDO_FS.contains(fsType);
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

    /** 通过 lsblk（JSON）一次性采集物理磁盘、Device Mapper 与 LVM 拓扑数据（无需 sudo） */
    private LsblkData loadLsblkData() {
        LsblkData data = new LsblkData();
        try {
            // lsblk 读取 /sys，无需 root；用普通执行而非 sudo，保证无免密 sudo 环境下仍能采集
            ExecResult result =
                    commandExecutor.exec("lsblk", "-J", "-b", "-o", "NAME,SIZE,TYPE,FSTYPE,MODEL,SERIAL,MOUNTPOINTS");
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
        if ("lvm".equals(type)) {
            // LVM 逻辑卷：既是 Device Mapper 设备，也是 LV 拓扑
            addLvmNode(node, data);
        } else if ("dm".equals(type)) {
            // 独立的 device mapper 设备（非 LVM 逻辑卷，如 dm-crypt/md 等）归入 DM 集合
            data.deviceMappers.add(buildMapper(node));
        } else if ("disk".equals(type)) {
            // 整盘或分区若挂有 type=lvm 子节点即为物理卷 PV
            registerPvIfLvmChild(node, data);
            if (isRealDisk(node.path("name").asText(""))) {
                data.physicalDisks.add(buildPhysicalDisk(node));
            }
        } else if ("part".equals(type)) {
            // 分区可能是物理卷（PV），其 children 挂有 type=lvm 的逻辑卷
            registerPvIfLvmChild(node, data);
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                collectLsblkNode(child, data);
            }
        }
    }

    /** 解析并登记一个逻辑卷（type=lvm）节点：LV 拓扑 + Device Mapper */
    private void addLvmNode(JsonNode node, LsblkData data) {
        String lvName = node.path("name").asText(""); // 形如 vg0-root
        long size = parseLongVal(node.path("size").asText());
        String vg = lvVg(lvName);

        MonitorOverview.LogicalVolume lv = new MonitorOverview.LogicalVolume();
        lv.setName(lvName);
        lv.setVg(vg);
        lv.setSizeBytes(size);
        data.logicalVolumes.add(lv);

        data.deviceMappers.add(buildMapper(node));

        if (!vg.isEmpty()) {
            VgAgg agg = data.vgAgg.computeIfAbsent(vg, k -> new VgAgg());
            agg.lvSize += size;
            agg.lvCount++;
        }
    }

    /** 若整盘或分区挂有 type=lvm 子节点（即物理卷 PV），登记 PV 并累计所属 VG 的容量 */
    private void registerPvIfLvmChild(JsonNode node, LsblkData data) {
        JsonNode children = node.path("children");
        if (!children.isArray()) {
            return;
        }
        String vg = "";
        for (JsonNode child : children) {
            if ("lvm".equals(child.path("type").asText(""))) {
                vg = lvVg(child.path("name").asText(""));
                break;
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

    /** LVM 逻辑卷名（形如 vg0-root）推导卷组名：取第一个连字符之前的部分 */
    private String lvVg(String lvName) {
        int idx = lvName.indexOf('-');
        return idx > 0 ? lvName.substring(0, idx) : "";
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

    /** 尝试从 /sys/block/<dev>/device/<attr> 读取磁盘属性（如 model/serial），失败返回空串 */
    private String readSysfsAttr(String devName, String attr) {
        try {
            String base = devName;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/sys/block", base, "device", attr));
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 由 lsblk 节点构建物理磁盘（仅含真实磁盘及其分区，分区带文件系统类型） */
    private MonitorOverview.PhysicalDisk buildPhysicalDisk(JsonNode node) {
        MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
        String devName = node.path("name").asText("");
        disk.setName("/dev/" + devName);
        // 优先取 lsblk 的 MODEL/SERIAL，缺失时读取 /sys/block 属性
        String model = nullToEmpty(node.path("model").asText());
        String serial = nullToEmpty(node.path("serial").asText());
        if (model.isEmpty()) {
            model = readSysfsAttr(devName, "model");
        }
        if (serial.isEmpty()) {
            serial = readSysfsAttr(devName, "serial");
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
                p.setMount(firstMount(child.path("mountpoints")));
                parts.add(p);
            }
        }
        disk.setPartitions(parts.isEmpty() ? List.of() : parts);
        return disk;
    }

    /** 由 lsblk 节点构建 Device Mapper 设备 */
    private MonitorOverview.DeviceMapper buildMapper(JsonNode node) {
        MonitorOverview.DeviceMapper mapper = new MonitorOverview.DeviceMapper();
        mapper.setName("/dev/" + node.path("name").asText());
        mapper.setSizeBytes(parseLongVal(node.path("size").asText()));
        mapper.setFsType(node.path("fstype").asText(""));
        mapper.setMount(firstMount(node.path("mountpoints")));
        return mapper;
    }

    /** 取 lsblk mountpoints 数组第一个非空挂载点，无则空串 */
    private String firstMount(JsonNode mountpoints) {
        if (mountpoints.isArray()) {
            for (JsonNode m : mountpoints) {
                if (!m.isNull() && !m.asText().isEmpty()) {
                    return m.asText();
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

    /** 由 OSHI 构建真实物理磁盘（型号/序列号缺失时尝试 /sys/block 读取） */
    private MonitorOverview.PhysicalDisk buildPhysicalDiskFromOshi(HWDiskStore store) {
        MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
        disk.setName(store.getName());
        String model = nullToEmpty(store.getModel());
        String serial = nullToEmpty(store.getSerial());
        if (model.isEmpty()) {
            model = readSysfsAttr(store.getName(), "model");
        }
        if (serial.isEmpty()) {
            serial = readSysfsAttr(store.getName(), "serial");
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
            parts.add(p);
        }
        disk.setPartitions(parts.isEmpty() ? List.of() : parts);
        return disk;
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

    /** 解析 LV 逻辑卷（名称 / 卷组 / 大小） */
    private List<MonitorOverview.LogicalVolume> parseLvs() {
        List<MonitorOverview.LogicalVolume> list = new ArrayList<>();
        for (JsonNode r : runLvmJson("lvs", "lv_name,vg_name,lv_size")) {
            MonitorOverview.LogicalVolume lv = new MonitorOverview.LogicalVolume();
            lv.setName(r.path("lv_name").asText());
            lv.setVg(r.path("vg_name").asText());
            lv.setSizeBytes(parseLongVal(r.path("lv_size").asText()));
            list.add(lv);
        }
        return list.isEmpty() ? List.of() : list;
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
