package com.serverpanel.monitor.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 网络信息快照：主机网卡明细 + Docker 虚拟网络 + 汇总统计。
 *
 * <p>由 {@code NetworkCollector} 每 5s 采集一次并缓存，经
 * {@code GET /api/v1/monitor/network} 对外提供。刻意与实时帧（MetricFrame）解耦：
 * 网卡明细字段多、体量大，不适合塞进 Redis 环形缓存与 WebSocket 的每帧推送。
 *
 * @author zhaodc
 * @since 2026-09-20
 */
@Data
public class NetworkInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 采集时间戳（毫秒） */
    private long ts;

    /** 汇总统计 */
    private NetworkSummary summary;

    /** 主机网卡明细（含物理网卡与 Docker/虚拟设备），已按类型排序 */
    private List<NetInterface> interfaces;

    /** Docker 虚拟网络列表（含接入的容器端点） */
    private List<DockerNetwork> dockerNetworks;

    /** 采集异常时的可读说明（正常为 null），前端据此提示而非空白 */
    private String error;

    /**
     * 单块网卡明细（内核视角，容器部署时经 HOST_SYSROOT 读宿主机 /sys 与 /proc）。
     */
    @Data
    public static class NetInterface implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 内核接口名，如 enp6s0 / docker0 / br-xxxx / vethxxxx */
        private String name;

        /** 接口索引 ifindex */
        private int index;

        /** 接口类型：physical / bond / bridge / veth / tunnel / virtual / loopback */
        private String category;

        /** 接口类型中文描述（含 Docker 语义），供页面直接展示 */
        private String typeLabel;

        /** 管理状态：operstate 取值 up / down / unknown / lowerlayerdown 等 */
        private String operState;

        /** 是否可用：管理 UP 且链路连通（carrier） */
        private boolean up;

        /** 管理 UP（IFF_UP，不含链路连通性判断） */
        private boolean adminUp;

        /** 是否检测到载波（物理链路连通，如网线插入） */
        private boolean carrier;

        /** 是否回环接口 */
        private boolean loopback;

        /** 是否为网桥设备 */
        private boolean bridge;

        /** 是否为绑定（bond）设备 */
        private boolean bond;

        /** 所属上层设备名（veth 挂在网桥上时为其网桥名） */
        private String master;

        /** 网桥的端口成员名列表（仅网桥非空） */
        private List<String> bridgePorts;

        /** 是否与 Docker 相关（docker0 / br-* 网桥及其上的 veth） */
        private boolean dockerRelated;

        /** 关联的 Docker 网络名（能解析到时非空） */
        private String dockerNetwork;

        /** MAC 地址 */
        private String mac;

        /** MTU */
        private int mtu;

        /** 主 IPv4 地址（兼容字段，带前缀时形如 192.168.1.10/24） */
        private String ipv4;

        /** 主地址 CIDR：优先 IPv4，无 IPv4 时取首个 IPv6 */
        private String cidr;

        /** 全部 IPv4 地址（带前缀长度） */
        private List<String> ipv4List;

        /** 全部 IPv6 地址（带前缀长度） */
        private List<String> ipv6List;

        /** 链路速率 Mbps（未知为 -1） */
        private long speed;

        /** 双工模式：full / half / unknown */
        private String duplex;

        /** 内核驱动名，如 r8169 / bridge / veth */
        private String driver;

        /** 总线地址（物理网卡为 PCI 槽位，如 0000:06:00.0） */
        private String busInfo;

        /** PCI 设备标识（厂商:设备，如 10EC:8125） */
        private String vendorId;

        /** 厂商名（常见厂商映射为可读名称，未知时为原标识） */
        private String vendor;

        /** 接口别名 ifalias（未设置为空串） */
        private String alias;

        /** 累计接收字节 */
        private long rxBytes;

        /** 累计发送字节 */
        private long txBytes;

        /** 累计接收包数 */
        private long rxPackets;

        /** 累计发送包数 */
        private long txPackets;

        /** 接收错误数 */
        private long rxErrors;

        /** 发送错误数 */
        private long txErrors;

        /** 接收丢弃数 */
        private long rxDropped;

        /** 发送丢弃数 */
        private long txDropped;

        /** 接收速率 KB/s（差分值，首帧为 0） */
        private double rxRate;

        /** 发送速率 KB/s（差分值，首帧为 0） */
        private double txRate;

        /** 接收包速率 包/s（差分值） */
        private double rxPacketRate;

        /** 发送包速率 包/s（差分值） */
        private double txPacketRate;
    }

    /**
     * Docker 网络（对应 {@code docker network inspect} 的关键字段）。
     */
    @Data
    public static class DockerNetwork implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 网络 ID（12 位短 ID） */
        private String id;

        /** 网络名（bridge / host / none / 自定义名） */
        private String name;

        /** 网络驱动：bridge / host / overlay / null */
        private String driver;

        /** 作用域：local / swarm */
        private String scope;

        /** 是否内部网络（禁止外部访问） */
        private boolean internal;

        /** 是否允许手动挂载容器 */
        private boolean attachable;

        /** 是否启用 IPv6 */
        private boolean ipv6Enabled;

        /** 子网 CIDR，如 172.22.0.0/16 */
        private String subnet;

        /** 网关地址 */
        private String gateway;

        /** 宿主侧网桥名（docker0 / br-xxxx；host、none 等无网桥时为空串） */
        private String bridgeName;

        /** 网络创建时间（毫秒时间戳，未知为 0） */
        private long createdAt;

        /** 接入本网络的容器数量 */
        private int containerCount;

        /** 接入本网络的容器端点列表 */
        private List<NetworkAttachment> containers;
    }

    /**
     * 容器在网络中的端点（容器名 / 容器在该网络的 IPv4 / MAC）。
     */
    @Data
    public static class NetworkAttachment implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 容器 ID（12 位短 ID） */
        private String containerId;

        /** 容器名 */
        private String containerName;

        /** 容器在该网络内的 IPv4（带掩码长度，如 172.22.0.2/16） */
        private String ipv4;

        /** 容器端点 MAC 地址 */
        private String mac;
    }

    /**
     * 网络汇总统计。
     */
    @Data
    public static class NetworkSummary implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 接口总数（不含回环） */
        private int total;

        /** 可用接口数（管理 UP 且链路连通） */
        private int up;

        /** 物理网卡数 */
        private int physical;

        /** 网桥数 */
        private int bridge;

        /** 容器虚拟网卡（veth）数 */
        private int veth;

        /** 与 Docker 相关的接口数 */
        private int dockerRelated;

        /** Docker 网络数 */
        private int dockerNetworks;

        /** 接入 Docker 网络的容器数（按容器去重） */
        private int dockerContainers;

        /** 全部接口累计接收字节（不含回环） */
        private long totalRxBytes;

        /** 全部接口累计发送字节（不含回环） */
        private long totalTxBytes;

        /** 全部接口实时接收速率合计 KB/s */
        private double rxRate;

        /** 全部接口实时发送速率合计 KB/s */
        private double txRate;

        /** 默认网关（IPv4，无默认路由为空串） */
        private String defaultGateway;

        /** 默认出口网卡名 */
        private String defaultInterface;
    }
}
