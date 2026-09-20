package com.serverpanel.monitor.dto;

import java.util.List;

import lombok.Data;

/**
 * 系统概览（静态信息 + 最新一帧）。
 */
@Data
public class MonitorOverview {

    private String hostname;

    private String os;

    private String kernel;

    private String cpuModel;

    private int cpuPhysicalCores;

    private int cpuLogicalCores;

    /** 磁盘挂载点列表（逻辑文件系统） */
    private List<DiskInfo> disks;

    /** 物理磁盘详细信息列表 */
    private List<PhysicalDisk> physicalDisks;

    /** Device Mapper 设备列表（LVM 逻辑卷映射 / dm-* 等），如根分区为 LVM 则在此展示 */
    private List<DeviceMapper> deviceMappers;

    /** LVM 信息（非 Linux 或无 lvm2 时为空） */
    private LvmInfo lvm;

    /** 网卡列表 */
    private List<NetInterface> interfaces;

    /** 最新一帧 */
    private MetricFrame latest;

    @Data
    public static class DiskInfo {
        private String mount;
        private String fsType;
        private long totalBytes;
        private long usableBytes;
        /** 使用率 0-100 */
        private double usage;
    }

    /** 物理磁盘详细信息（对应 OSHI HDiskStore） */
    @Data
    public static class PhysicalDisk {
        /** 设备路径，如 /dev/sda */
        private String name;

        private String model;
        private String serial;
        private long sizeBytes;
        /** 该物理磁盘上的分区列表 */
        private List<Partition> partitions;
    }

    @Data
    public static class Partition {
        /** 分区路径，如 /dev/sda1 */
        private String name;
        /** 挂载点（未挂载则空串） */
        private String mount;

        private long sizeBytes;
        /** 分区类型，如 linux */
        private String type;
    }

    /** Device Mapper 设备（LVM 逻辑卷映射 / dm-* 等） */
    @Data
    public static class DeviceMapper {
        /** 设备路径，如 /dev/mapper/vg0-root 或 /dev/dm-0 */
        private String name;

        private long sizeBytes;
        /** 文件系统类型（格式化方式），如 ext4 / xfs */
        private String fsType;
        /** 挂载点（未挂载则空串） */
        private String mount;
    }

    /** LVM 信息（由 pvs / vgs / lvs 命令解析） */
    @Data
    public static class LvmInfo {
        private List<PhysicalVolume> physicalVolumes;
        private List<VolumeGroup> volumeGroups;
        private List<LogicalVolume> logicalVolumes;
    }

    @Data
    public static class PhysicalVolume {
        /** 物理卷路径，如 /dev/sda2 */
        private String name;
        /** 所属卷组名 */
        private String vg;

        private long sizeBytes;
        private long freeBytes;
    }

    @Data
    public static class VolumeGroup {
        private String name;
        private int pvCount;
        private int lvCount;
        private long sizeBytes;
        private long freeBytes;
    }

    @Data
    public static class LogicalVolume {
        private String name;
        /** 所属卷组名 */
        private String vg;

        private long sizeBytes;
    }

    @Data
    public static class NetInterface {
        private String name;
        private String ipv4;
        /** 接口速率 Mbps（未知为 -1） */
        private long speed;
    }
}
