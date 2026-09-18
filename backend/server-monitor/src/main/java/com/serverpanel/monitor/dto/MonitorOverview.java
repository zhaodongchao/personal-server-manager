package com.serverpanel.monitor.dto;

import lombok.Data;

import java.util.List;

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

    /** 磁盘挂载点列表 */
    private List<DiskInfo> disks;

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

    @Data
    public static class NetInterface {
        private String name;
        private String ipv4;
        /** 接口速率 Mbps（未知为 -1） */
        private long speed;
    }
}
