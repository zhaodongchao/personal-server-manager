package com.serverpanel.monitor.service;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

        List<MonitorOverview.DiskInfo> disks = new ArrayList<>();
        for (OSFileStore store : os.getFileSystem().getFileStores(true)) {
            MonitorOverview.DiskInfo disk = new MonitorOverview.DiskInfo();
            disk.setMount(store.getMount());
            disk.setFsType(store.getType());
            disk.setTotalBytes(store.getTotalSpace());
            disk.setUsableBytes(store.getUsableSpace());
            long total = store.getTotalSpace();
            disk.setUsage(total == 0 ? 0 : round2((total - store.getUsableSpace()) * 100.0 / total));
            disks.add(disk);
        }
        vo.setDisks(disks);
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

    /** 采集物理磁盘详细信息（OSHI HWDiskStore） */
    private List<MonitorOverview.PhysicalDisk> collectPhysicalDisks(HardwareAbstractionLayer hardware) {
        List<MonitorOverview.PhysicalDisk> list = new ArrayList<>();
        for (HWDiskStore store : hardware.getDiskStores()) {
            MonitorOverview.PhysicalDisk disk = new MonitorOverview.PhysicalDisk();
            disk.setName(store.getName());
            disk.setModel(store.getModel());
            disk.setSerial(store.getSerial());
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

    /** 采集 LVM 信息；非 Linux 或无 lvm2 工具时返回空结构，不中断概览 */
    private MonitorOverview.LvmInfo collectLvm() {
        MonitorOverview.LvmInfo lvm = new MonitorOverview.LvmInfo();
        lvm.setPhysicalVolumes(parsePvs());
        lvm.setVolumeGroups(parseVgs());
        lvm.setLogicalVolumes(parseLvs());
        return lvm;
    }

    /** 执行 LVM 只读查询命令，返回去除空白的行为列表；失败按空处理 */
    private List<String> runLvmCommand(String... argv) {
        try {
            ExecResult result = commandExecutor.exec(argv);
            if (!result.isSuccess()) {
                return List.of();
            }
            String stdout = result.getStdout();
            if (stdout == null || stdout.isBlank()) {
                return List.of();
            }
            return stdout.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        } catch (Exception e) {
            log.warn("LVM 查询命令 {} 失败：{}", argv[0], e.getMessage());
            return List.of();
        }
    }

    /** 解析 pvs：PV 物理卷（路径:卷组:大小:可用） */
    private List<MonitorOverview.PhysicalVolume> parsePvs() {
        List<MonitorOverview.PhysicalVolume> list = new ArrayList<>();
        for (String line : runLvmCommand(
                "pvs",
                "--noheadings",
                "--units",
                "b",
                "--nosuffix",
                "--separator",
                ":",
                "-o",
                "pv_name,vg_name,pv_size,pv_free")) {
            String[] f = line.split(":");
            if (f.length < 4) {
                continue;
            }
            MonitorOverview.PhysicalVolume pv = new MonitorOverview.PhysicalVolume();
            pv.setName(f[0].trim());
            pv.setVg(f[1].trim());
            pv.setSizeBytes(parseLongVal(f[2]));
            pv.setFreeBytes(parseLongVal(f[3]));
            list.add(pv);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 解析 vgs：VG 卷组（名称:PV数:LV数:大小:可用） */
    private List<MonitorOverview.VolumeGroup> parseVgs() {
        List<MonitorOverview.VolumeGroup> list = new ArrayList<>();
        for (String line : runLvmCommand(
                "vgs",
                "--noheadings",
                "--units",
                "b",
                "--nosuffix",
                "--separator",
                ":",
                "-o",
                "vg_name,pv_count,lv_count,vg_size,vg_free")) {
            String[] f = line.split(":");
            if (f.length < 5) {
                continue;
            }
            MonitorOverview.VolumeGroup vg = new MonitorOverview.VolumeGroup();
            vg.setName(f[0].trim());
            vg.setPvCount(parseIntVal(f[1]));
            vg.setLvCount(parseIntVal(f[2]));
            vg.setSizeBytes(parseLongVal(f[3]));
            vg.setFreeBytes(parseLongVal(f[4]));
            list.add(vg);
        }
        return list.isEmpty() ? List.of() : list;
    }

    /** 解析 lvs：LV 逻辑卷（名称:卷组:大小） */
    private List<MonitorOverview.LogicalVolume> parseLvs() {
        List<MonitorOverview.LogicalVolume> list = new ArrayList<>();
        for (String line : runLvmCommand(
                "lvs",
                "--noheadings",
                "--units",
                "b",
                "--nosuffix",
                "--separator",
                ":",
                "-o",
                "lv_name,vg_name,lv_size")) {
            String[] f = line.split(":");
            if (f.length < 3) {
                continue;
            }
            MonitorOverview.LogicalVolume lv = new MonitorOverview.LogicalVolume();
            lv.setName(f[0].trim());
            lv.setVg(f[1].trim());
            lv.setSizeBytes(parseLongVal(f[2]));
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
