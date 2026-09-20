package com.serverpanel.monitor.service;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
        vo.setPhysicalDisks(collectPhysicalDisks(hardware));
        vo.setLvm(collectLvm());

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
            ExecResult result = commandExecutor.execSudo("findmnt", "-J", "-b", "-o", "TARGET,FSTYPE,SIZE,AVAIL");
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

    /** 采集物理磁盘：优先 sudo lsblk（树状嵌套），失败回退 OSHI */
    private List<MonitorOverview.PhysicalDisk> collectPhysicalDisks(HardwareAbstractionLayer hardware) {
        List<MonitorOverview.PhysicalDisk> fromLsblk = collectPhysicalDisksFromLsblk();
        return fromLsblk.isEmpty() ? collectPhysicalDisksFromOshi(hardware) : fromLsblk;
    }

    /** 通过 sudo lsblk（JSON）采集物理磁盘及其分区（type=disk 为磁盘，type=part 为分区） */
    private List<MonitorOverview.PhysicalDisk> collectPhysicalDisksFromLsblk() {
        try {
            ExecResult result = commandExecutor.execSudo(
                    "lsblk", "-J", "-b", "-o", "NAME,SIZE,TYPE,FSTYPE,MODEL,SERIAL,MOUNTPOINTS");
            if (!result.isSuccess()
                    || result.getStdout() == null
                    || result.getStdout().isBlank()) {
                return List.of();
            }
            List<MonitorOverview.PhysicalDisk> list = new ArrayList<>();
            JsonNode root = objectMapper.readTree(result.getStdout());
            for (JsonNode node : root.path("blockdevices")) {
                if (!"disk".equals(node.path("type").asText())) {
                    continue;
                }
                MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
                disk.setName("/dev/" + node.path("name").asText());
                disk.setModel(nullToEmpty(node.path("model").asText()));
                disk.setSerial(nullToEmpty(node.path("serial").asText()));
                disk.setSizeBytes(parseLongVal(node.path("size").asText()));
                List<MonitorOverview.Partition> parts = new ArrayList<>();
                for (JsonNode child : node.path("children")) {
                    if (!"part".equals(child.path("type").asText())) {
                        continue;
                    }
                    MonitorOverview.Partition p = new MonitorOverview.Partition();
                    p.setName("/dev/" + child.path("name").asText());
                    p.setSizeBytes(parseLongVal(child.path("size").asText()));
                    p.setType(child.path("fstype").asText(""));
                    p.setMount(firstMount(child.path("mountpoints")));
                    parts.add(p);
                }
                disk.setPartitions(parts.isEmpty() ? List.of() : parts);
                list.add(disk);
            }
            return list.isEmpty() ? List.of() : list;
        } catch (Exception e) {
            log.warn("lsblk 采集物理磁盘失败，回退 OSHI：{}", e.getMessage());
            return List.of();
        }
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

    /** 回退：OSHI 物理磁盘（HWDiskStore） */
    private List<MonitorOverview.PhysicalDisk> collectPhysicalDisksFromOshi(HardwareAbstractionLayer hardware) {
        List<MonitorOverview.PhysicalDisk> list = new ArrayList<>();
        for (HWDiskStore store : hardware.getDiskStores()) {
            MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
            disk.setName(store.getName());
            disk.setModel(nullToEmpty(store.getModel()));
            disk.setSerial(nullToEmpty(store.getSerial()));
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
            list.add(disk);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 空串归一化；lsblk 无型号/序列号时输出为空，避免前端展示 null */
    private String nullToEmpty(String s) {
        return s == null || "n/a".equals(s) ? "" : s;
    }

    /** 采集 LVM 信息；非 Linux 或无 lvm2 工具时返回空结构，不中断概览 */
    private MonitorOverview.LvmInfo collectLvm() {
        MonitorOverview.LvmInfo lvm = new MonitorOverview.LvmInfo();
        lvm.setPhysicalVolumes(parsePvs());
        lvm.setVolumeGroups(parseVgs());
        lvm.setLogicalVolumes(parseLvs());
        return lvm;
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
