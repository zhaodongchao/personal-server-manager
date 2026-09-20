package com.serverpanel.monitor.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import com.serverpanel.framework.docker.DockerClientProvider;
import com.serverpanel.monitor.dto.NetworkInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;

/**
 * 网络信息采集器：主机真实网卡明细 + Docker 虚拟网络。
 *
 * <p>采集口径与数据来源：
 * <ul>
 *   <li>网卡枚举与元数据：{@code /sys/class/net/*}（类别、状态、MAC、MTU、速率、双工、
 *       驱动、PCI 槽位、网桥端口）。面板容器以 host 网络模式运行且挂载宿主机根，
 *       故读到的是宿主机内核视角（含 docker0、br-* 与 veth）。</li>
 *   <li>收发计数与速率：{@code /proc/net/dev}（单文件成本低），速率按两次采集差分。</li>
 *   <li>IPv4 地址与前缀：OSHI（内核未提供文件级 IPv4 视图）；
 *       IPv6 优先 {@code /proc/net/if_inet6}（自带前缀长度与接口名）。</li>
 *   <li>默认路由：{@code /proc/net/route}。</li>
 *   <li>Docker 网络与容器端点：docker-java 走 {@code /var/run/docker.sock}，
 *       宿主网桥名与 {@code br-*} / {@code docker0} 匹配后回填到网卡明细。</li>
 * </ul>
 *
 * <p>Docker 侧数据 30s 缓存一次（网络拓扑变化不频繁），其余部分每轮全量采集。
 *
 * @author zhaodc
 * @since 2026-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkCollector {

    /** 网卡 sysfs 目录（容器部署时经 HostFileAccess 映射到宿主机） */
    private static final String SYS_NET = "/sys/class/net";

    /** 网卡收发计数（内核视图） */
    private static final String PROC_NET_DEV = "/proc/net/dev";

    /** IPv6 地址表（含前缀长度与接口名） */
    private static final String PROC_NET_IF_INET6 = "/proc/net/if_inet6";

    /** IPv4 路由表（用于推导默认网关） */
    private static final String PROC_NET_ROUTE = "/proc/net/route";

    /** IFF_UP：接口管理状态为启用 */
    private static final long IFF_UP = 0x1L;

    /** IFF_LOOPBACK：回环接口 */
    private static final long IFF_LOOPBACK = 0x8L;

    /** 内核 speed 未知时的哨兵值（u32 最大值） */
    private static final long SPEED_UNKNOWN = 1_000_000L;

    /** Docker 网络缓存时长（毫秒） */
    private static final long DOCKER_CACHE_MS = 30_000L;

    /** 常见 PCI 厂商 ID → 厂商名（未收录时直接展示原始标识） */
    private static final Map<String, String> PCI_VENDORS = Map.ofEntries(
            Map.entry("8086", "Intel"),
            Map.entry("10EC", "Realtek"),
            Map.entry("14E4", "Broadcom"),
            Map.entry("15B3", "Mellanox"),
            Map.entry("1AF4", "Red Hat (virtio)"),
            Map.entry("1D0F", "Amazon"),
            Map.entry("1022", "AMD"),
            Map.entry("10DE", "NVIDIA"));

    private final HostFileAccess hostFs;

    private final DockerClientProvider dockerClientProvider;

    private final SystemInfo systemInfo = new SystemInfo();

    /** 上一轮各接口计数（名称 → [rxBytes, rxPackets, rxErrors, rxDropped, txBytes, txPackets, txErrors, txDropped]） */
    private final Map<String, long[]> prevCounters = new LinkedHashMap<>();

    /** 上一轮采集时间戳（速率差分基准） */
    private long prevTs;

    /** Docker 网络缓存（含采集时间） */
    private volatile List<NetworkInfo.DockerNetwork> dockerCache = List.of();

    private volatile long dockerCacheTs;

    /**
     * 采集一次网络信息快照。任何单点失败都不抛异常：能采多少采多少，
     * 失败信息写入 {@code error} 字段供页面提示。
     */
    public synchronized NetworkInfo collect() {
        long now = System.currentTimeMillis();
        NetworkInfo info = new NetworkInfo();
        info.setTs(now);
        try {
            List<NetworkInfo.DockerNetwork> networks = dockerNetworks();
            Map<String, String> bridgeToNetwork = bridgeToNetwork(networks);

            Map<String, long[]> devCounters = readProcNetDev();
            Map<String, List<String>> ipv6ByIface = readIpv6Addresses();
            Map<String, NetworkIF> oshiIfs = readOshiInterfaces();

            double seconds = prevTs > 0 && now > prevTs ? (now - prevTs) / 1000.0 : 0;
            boolean dockerUnknown = networks.isEmpty();

            List<NetworkInfo.NetInterface> list = new ArrayList<>();
            Map<String, long[]> current = new LinkedHashMap<>();
            for (String name : hostFs.listNames(SYS_NET)) {
                long[] counters = devCounters.get(name);
                NetworkInfo.NetInterface item = buildInterface(
                        name, counters, oshiIfs.get(name), ipv6ByIface.get(name), bridgeToNetwork, dockerUnknown, seconds);
                if (counters != null) {
                    current.put(name, counters);
                }
                list.add(item);
            }
            prevCounters.clear();
            prevCounters.putAll(current);
            prevTs = now;

            info.setDockerNetworks(networks);
            info.setInterfaces(sortInterfaces(list));
            info.setSummary(buildSummary(list, networks));
        } catch (Exception e) {
            log.error("网络信息采集失败：{}", e.getMessage());
            info.setInterfaces(List.of());
            info.setDockerNetworks(List.of());
            info.setError("网络信息采集失败：" + e.getMessage());
        }
        return info;
    }

    // ==================== 单块网卡 ====================

    /** 组装单块网卡明细：sysfs 元数据 + 地址 + 计数与速率 */
    private NetworkInfo.NetInterface buildInterface(
            String name,
            long[] counters,
            NetworkIF oshi,
            List<String> ipv6FromProc,
            Map<String, String> bridgeToNetwork,
            boolean dockerUnknown,
            double seconds) {
        String dir = SYS_NET + "/" + name;
        NetworkInfo.NetInterface item = new NetworkInfo.NetInterface();
        item.setName(name);
        item.setIndex((int) parseLong(hostFs.readFile(dir + "/ifindex").trim()));
        item.setMac(hostFs.readFile(dir + "/address").trim());
        item.setMtu((int) parseLong(hostFs.readFile(dir + "/mtu").trim()));
        item.setOperState(hostFs.readFile(dir + "/operstate").trim());
        item.setAlias(hostFs.readFile(dir + "/ifalias").trim());

        String carrierText = hostFs.readFile(dir + "/carrier").trim();
        item.setCarrier("1".equals(carrierText));
        long flags = parseHex(hostFs.readFile(dir + "/flags").trim());
        item.setAdminUp((flags & IFF_UP) != 0);
        item.setLoopback((flags & IFF_LOOPBACK) != 0);
        // carrier 文件在部分虚拟设备上不存在，此时退回 operstate 判断
        item.setUp(item.isAdminUp() && (item.isCarrier() || "up".equals(item.getOperState())));

        // 驱动与归属：物理网卡读 device/uevent（含驱动 / PCI 槽位 / 厂商），虚拟设备读 uevent 的 DEVTYPE
        fillDeviceInfo(item, dir);
        // 是否挂在真实总线上（物理网卡存在 device 软链，网桥 / veth 等虚拟设备没有）
        boolean hasDevice = hostFs.exists(dir + "/device");
        boolean bridge = hostFs.exists(dir + "/bridge");
        item.setBridge(bridge);
        item.setBond(hostFs.exists(dir + "/bonding"));
        String master = hostFs.linkName(dir + "/master");
        item.setMaster(master);
        item.setBridgePorts(bridge ? hostFs.listNames(dir + "/brif") : List.of());

        long speed = parseLong(hostFs.readFile(dir + "/speed").trim());
        item.setSpeed(speed <= 0 || speed >= SPEED_UNKNOWN ? -1 : speed);
        item.setDuplex(hostFs.readFile(dir + "/duplex").trim().toLowerCase(Locale.ROOT));

        fillAddresses(item, oshi, ipv6FromProc);

        // Docker 归属：优先按宿主网桥名匹配，匹配不到时（Docker 不可用）退回命名约定
        String network = bridgeToNetwork.get(name);
        if (network == null && !master.isEmpty()) {
            network = bridgeToNetwork.get(master);
        }
        boolean dockerRelated = network != null;
        if (!dockerRelated && dockerUnknown) {
            dockerRelated = "docker0".equals(name) || name.startsWith("br-") || name.startsWith("veth");
        }
        item.setDockerRelated(dockerRelated);
        item.setDockerNetwork(network == null ? "" : network);

        String category = classify(name, item.isLoopback(), bridge, item.isBond(), hasDevice);
        item.setCategory(category);
        item.setTypeLabel(typeLabel(category, dockerRelated));
        if (item.getDriver() == null || item.getDriver().isEmpty()) {
            item.setDriver(defaultDriver(category));
        }

        normalizeStrings(item);
        fillCounters(item, counters, seconds);
        return item;
    }

    /**
     * 字符串字段归一：DTO 对外声明这些字段为非空，sysfs 未提供时（如 lo 无
     * device/uevent、虚拟设备无 PCI 槽位）统一落空串，避免序列化与前端拿到 null。
     *
     * @param item 待归一的网卡明细
     */
    private void normalizeStrings(NetworkInfo.NetInterface item) {
        if (item.getDriver() == null) {
            item.setDriver("");
        }
        if (item.getBusInfo() == null) {
            item.setBusInfo("");
        }
        if (item.getVendorId() == null) {
            item.setVendorId("");
        }
        if (item.getVendor() == null) {
            item.setVendor("");
        }
    }

    /** 物理网卡归属信息：device/uevent（DRIVER / PCI_SLOT_NAME / PCI_ID）优先，虚拟设备取 uevent 的 DEVTYPE */
    private void fillDeviceInfo(NetworkInfo.NetInterface item, String dir) {
        String uevent = hostFs.readFile(dir + "/device/uevent");
        if (uevent.isBlank()) {
            String netUevent = hostFs.readFile(dir + "/uevent");
            for (String line : netUevent.split("\n")) {
                if (line.startsWith("DEVTYPE=")) {
                    item.setDriver(line.substring("DEVTYPE=".length()).trim());
                    break;
                }
            }
            return;
        }
        for (String line : uevent.split("\n")) {
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            switch (key) {
                case "DRIVER" -> item.setDriver(value);
                case "PCI_SLOT_NAME" -> item.setBusInfo(value);
                case "PCI_ID" -> {
                    item.setVendorId(value);
                    item.setVendor(vendorName(value));
                }
                default -> {
                    // 其余 uevent 字段（PCI_CLASS / MODALIAS 等）不展示
                }
            }
        }
    }

    /** PCI 标识（10EC:8125）→ 厂商名；未收录时返回原标识 */
    private String vendorName(String pciId) {
        if (pciId == null || pciId.length() < 4) {
            return "";
        }
        String vendor = pciId.substring(0, 4).toUpperCase(Locale.ROOT);
        String name = PCI_VENDORS.get(vendor);
        return name == null ? "未知厂商(" + vendor + ")" : name;
    }

    /** 地址填充：IPv4 与前缀取 OSHI；IPv6 优先内核表（自带前缀长度） */
    private void fillAddresses(NetworkInfo.NetInterface item, NetworkIF oshi, List<String> ipv6FromProc) {
        List<String> ipv4List = new ArrayList<>();
        if (oshi != null) {
            String[] ipv4 = oshi.getIPv4addr();
            Short[] masks = oshi.getSubnetMasks();
            if (ipv4 != null) {
                for (int i = 0; i < ipv4.length; i++) {
                    String ip = ipv4[i];
                    if (ip == null || ip.isEmpty()) {
                        continue;
                    }
                    Short mask = masks != null && i < masks.length ? masks[i] : null;
                    ipv4List.add(mask == null ? ip : ip + "/" + mask);
                }
            }
        }
        List<String> ipv6List = new ArrayList<>();
        if (ipv6FromProc != null && !ipv6FromProc.isEmpty()) {
            ipv6List.addAll(ipv6FromProc);
        } else if (oshi != null && oshi.getIPv6addr() != null) {
            String[] ipv6 = oshi.getIPv6addr();
            Short[] prefixes = oshi.getPrefixLengths();
            for (int i = 0; i < ipv6.length; i++) {
                String ip = ipv6[i];
                if (ip == null || ip.isEmpty()) {
                    continue;
                }
                Short prefix = prefixes != null && i < prefixes.length ? prefixes[i] : null;
                ipv6List.add(prefix == null ? ip : ip + "/" + prefix);
            }
        }
        item.setIpv4List(ipv4List);
        item.setIpv6List(ipv6List);
        item.setIpv4(ipv4List.isEmpty() ? "" : ipv4List.get(0));
        if (!ipv4List.isEmpty()) {
            item.setCidr(ipv4List.get(0));
        } else {
            item.setCidr(ipv6List.isEmpty() ? "" : ipv6List.get(0));
        }
    }

    /** 计数与差分速率：累计量直接回填，速率按两次采集的时间差换算 */
    private void fillCounters(NetworkInfo.NetInterface item, long[] counters, double seconds) {
        if (counters == null) {
            return;
        }
        item.setRxBytes(counters[0]);
        item.setRxPackets(counters[1]);
        item.setRxErrors(counters[2]);
        item.setRxDropped(counters[3]);
        item.setTxBytes(counters[4]);
        item.setTxPackets(counters[5]);
        item.setTxErrors(counters[6]);
        item.setTxDropped(counters[7]);
        long[] prev = prevCounters.get(item.getName());
        if (prev == null || seconds <= 0) {
            return;
        }
        // 计数器回绕或重置时差值可能为负，取 0 避免速率毛刺
        item.setRxRate(round2(Math.max(0, counters[0] - prev[0]) / 1024.0 / seconds));
        item.setTxRate(round2(Math.max(0, counters[4] - prev[4]) / 1024.0 / seconds));
        item.setRxPacketRate(round2(Math.max(0, counters[1] - prev[1]) / seconds));
        item.setTxPacketRate(round2(Math.max(0, counters[5] - prev[5]) / seconds));
    }

    /** 类型归类：回环 / 绑定 / 网桥 / 容器虚拟以太 / 隧道 / 物理 / 其他虚拟 */
    private String classify(String name, boolean loopback, boolean bridge, boolean bond, boolean hasDevice) {
        if (loopback) {
            return "loopback";
        }
        if (bond) {
            return "bond";
        }
        if (bridge) {
            return "bridge";
        }
        if (name.startsWith("veth")) {
            return "veth";
        }
        if (name.startsWith("tun")
                || name.startsWith("tap")
                || name.startsWith("wg")
                || name.startsWith("ppp")
                || name.startsWith("sit")
                || name.startsWith("gre")
                || name.startsWith("ipip")
                || name.startsWith("he-")) {
            return "tunnel";
        }
        return hasDevice ? "physical" : "virtual";
    }

    /** 类型中文描述（区分 Docker 语义） */
    private String typeLabel(String category, boolean dockerRelated) {
        return switch (category) {
            case "physical" -> "物理网卡";
            case "bond" -> "绑定网卡(bond)";
            case "bridge" -> dockerRelated ? "Docker 网桥" : "网桥";
            case "veth" -> dockerRelated ? "容器虚拟网卡(veth)" : "虚拟以太(veth)";
            case "tunnel" -> "隧道接口";
            case "loopback" -> "回环接口";
            default -> "虚拟接口";
        };
    }

    /** 虚拟设备驱动的兜底取值（sysfs uevent 未提供时按类别推断） */
    private String defaultDriver(String category) {
        return switch (category) {
            case "bridge" -> "bridge";
            case "veth" -> "veth";
            case "loopback" -> "loopback";
            case "bond" -> "bonding";
            default -> "";
        };
    }

    /** 排序：物理网卡 → 绑定 → 网桥 → 隧道 → 容器虚拟网卡 → 其他虚拟 → 回环；同类别可用的在前 */
    private List<NetworkInfo.NetInterface> sortInterfaces(List<NetworkInfo.NetInterface> list) {
        List<NetworkInfo.NetInterface> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt((NetworkInfo.NetInterface i) -> categoryOrder(i.getCategory()))
                .thenComparing((NetworkInfo.NetInterface i) -> i.isUp() ? 0 : 1)
                .thenComparing(NetworkInfo.NetInterface::getName));
        return sorted;
    }

    private int categoryOrder(String category) {
        if (category == null) {
            return 90;
        }
        return switch (category) {
            case "physical" -> 0;
            case "bond" -> 1;
            case "bridge" -> 2;
            case "tunnel" -> 3;
            case "veth" -> 4;
            case "virtual" -> 5;
            case "loopback" -> 99;
            default -> 90;
        };
    }

    // ==================== 内核文件解析 ====================

    /**
     * 解析 {@code /proc/net/dev}。
     *
     * @return 接口名 → [rxBytes, rxPackets, rxErrors, rxDropped, txBytes, txPackets, txErrors, txDropped]
     */
    private Map<String, long[]> readProcNetDev() {
        Map<String, long[]> map = new LinkedHashMap<>();
        String content = hostFs.readFile(PROC_NET_DEV);
        if (content.isBlank()) {
            log.warn("读取 {} 失败，网卡收发计数与速率将不可用", PROC_NET_DEV);
            return map;
        }
        for (String line : content.split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue; // 跳过两行表头
            }
            String name = line.substring(0, idx).trim();
            String[] cols = line.substring(idx + 1).trim().split("\\s+");
            if (name.isEmpty() || cols.length < 12) {
                continue;
            }
            long[] values = new long[8];
            values[0] = parseLong(cols[0]);
            values[1] = parseLong(cols[1]);
            values[2] = parseLong(cols[2]);
            values[3] = parseLong(cols[3]);
            values[4] = parseLong(cols[8]);
            values[5] = parseLong(cols[9]);
            values[6] = parseLong(cols[10]);
            values[7] = parseLong(cols[11]);
            map.put(name, values);
        }
        return map;
    }

    /**
     * 解析 {@code /proc/net/if_inet6}：每行为「32 位十六进制地址 + ifindex + 前缀长度(十六进制)
     * + scope + flags + 接口名」。
     *
     * @return 接口名 → IPv6 地址列表（带前缀长度）
     */
    private Map<String, List<String>> readIpv6Addresses() {
        Map<String, List<String>> map = new HashMap<>();
        String content = hostFs.readFile(PROC_NET_IF_INET6);
        if (content.isBlank()) {
            return map;
        }
        for (String line : content.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 6 || cols[0].length() != 32) {
                continue;
            }
            String hex = cols[0];
            StringBuilder ip = new StringBuilder();
            for (int i = 0; i < 32; i += 4) {
                if (i > 0) {
                    ip.append(':');
                }
                ip.append(hex, i, i + 4);
            }
            int prefix = (int) parseHex(cols[2]);
            map.computeIfAbsent(cols[5], k -> new ArrayList<>()).add(ip + "/" + prefix);
        }
        return map;
    }

    // ==================== OSHI 补充 ====================

    /** OSHI 网卡枚举（按名称索引）：用于补齐内核未提供文件视图的 IPv4 地址 */
    private Map<String, NetworkIF> readOshiInterfaces() {
        Map<String, NetworkIF> map = new HashMap<>();
        try {
            for (NetworkIF netif : systemInfo.getHardware().getNetworkIFs(true)) {
                if (netif.getName() != null) {
                    map.put(netif.getName(), netif);
                }
            }
        } catch (Exception e) {
            log.warn("OSHI 网卡枚举失败（IPv4 地址与链路速率将缺失）：{}", e.getMessage());
        }
        return map;
    }

    // ==================== Docker 网络 ====================

    /** Docker 网络列表（30s 缓存；采集失败时保留上一次结果并记录 WARN） */
    private List<NetworkInfo.DockerNetwork> dockerNetworks() {
        long now = System.currentTimeMillis();
        if (now - dockerCacheTs < DOCKER_CACHE_MS) {
            return dockerCache;
        }
        try {
            DockerClient docker = dockerClientProvider.client();
            List<NetworkInfo.DockerNetwork> list = new ArrayList<>();
            for (Network net : docker.listNetworksCmd().exec()) {
                list.add(toDockerNetwork(detailOf(docker, net)));
            }
            list.sort(Comparator.comparing(
                    (NetworkInfo.DockerNetwork n) -> n.getName() == null ? "" : n.getName()));
            dockerCache = list;
        } catch (Exception e) {
            log.warn("Docker 网络采集失败（Docker 未运行或无 socket 权限）：{}", e.getMessage());
            if (dockerCache.isEmpty()) {
                dockerCache = List.of();
            }
        }
        dockerCacheTs = now;
        return dockerCache;
    }

    /** 宿主网桥名 → Docker 网络名（用于把 br-xxxx / docker0 关联网卡与网络） */
    private Map<String, String> bridgeToNetwork(List<NetworkInfo.DockerNetwork> networks) {
        Map<String, String> map = new LinkedHashMap<>();
        for (NetworkInfo.DockerNetwork net : networks) {
            if (net.getBridgeName() != null && !net.getBridgeName().isEmpty()) {
                map.putIfAbsent(net.getBridgeName(), net.getName());
            }
        }
        return map;
    }

    /**
     * 取网络详情：Docker 的 list 接口不返回容器端点（Containers 为空），
     * 必须逐个 inspect 才能拿到接入的容器；inspect 失败时回退 list 结果。
     *
     * @param docker Docker 客户端
     * @param net    list 接口返回的网络
     * @return 含容器端点的网络详情；inspect 不可用时返回原对象
     */
    private Network detailOf(DockerClient docker, Network net) {
        String id = net.getId();
        if (id == null || id.isEmpty()) {
            return net;
        }
        try {
            Network detail = docker.inspectNetworkCmd().withNetworkId(id).exec();
            return detail == null ? net : detail;
        } catch (Exception e) {
            log.debug("inspect Docker 网络 {} 失败，回退 list 结果：{}", id, e.getMessage());
            return net;
        }
    }

    /** docker-java Network 模型 → 展示模型 */
    private NetworkInfo.DockerNetwork toDockerNetwork(Network net) {
        NetworkInfo.DockerNetwork vo = new NetworkInfo.DockerNetwork();
        String fullId = net.getId() == null ? "" : net.getId();
        vo.setId(fullId.length() > 12 ? fullId.substring(0, 12) : fullId);
        vo.setName(nullToEmpty(net.getName()));
        vo.setDriver(nullToEmpty(net.getDriver()));
        vo.setScope(nullToEmpty(net.getScope()));
        vo.setInternal(Boolean.TRUE.equals(net.getInternal()));
        vo.setAttachable(Boolean.TRUE.equals(net.isAttachable()));
        vo.setIpv6Enabled(Boolean.TRUE.equals(net.getEnableIPv6()));
        vo.setCreatedAt(net.getCreated() == null ? 0L : net.getCreated().getTime());
        vo.setBridgeName(bridgeNameOf(net, fullId));
        // host / none 等网络没有 IPAM 配置，先落空串，有配置时再覆盖，保证契约稳定
        vo.setSubnet("");
        vo.setGateway("");

        Network.Ipam ipam = net.getIpam();
        if (ipam != null && ipam.getConfig() != null) {
            for (Network.Ipam.Config cfg : ipam.getConfig()) {
                if (cfg == null || cfg.getSubnet() == null || cfg.getSubnet().isEmpty()) {
                    continue;
                }
                vo.setSubnet(cfg.getSubnet());
                vo.setGateway(nullToEmpty(cfg.getGateway()));
                break;
            }
        }

        List<NetworkInfo.NetworkAttachment> attachments = new ArrayList<>();
        Map<String, Network.ContainerNetworkConfig> containers = net.getContainers();
        if (containers != null) {
            for (Map.Entry<String, Network.ContainerNetworkConfig> entry : containers.entrySet()) {
                NetworkInfo.NetworkAttachment attachment = new NetworkInfo.NetworkAttachment();
                String containerId = entry.getKey() == null ? "" : entry.getKey();
                attachment.setContainerId(containerId.length() > 12 ? containerId.substring(0, 12) : containerId);
                Network.ContainerNetworkConfig endpoint = entry.getValue();
                if (endpoint != null) {
                    attachment.setContainerName(nullToEmpty(endpoint.getName()));
                    attachment.setIpv4(nullToEmpty(endpoint.getIpv4Address()));
                    attachment.setMac(nullToEmpty(endpoint.getMacAddress()));
                }
                attachments.add(attachment);
            }
        }
        attachments.sort(Comparator.comparing(
                (NetworkInfo.NetworkAttachment a) -> a.getContainerName() == null ? "" : a.getContainerName()));
        vo.setContainers(attachments);
        vo.setContainerCount(attachments.size());
        return vo;
    }

    /**
     * 宿主侧网桥名：桥接驱动优先取 {@code com.docker.network.bridge.name} 选项，
     * 默认 bridge 网络固定为 {@code docker0}，其余按 {@code br-<网络 ID 前 12 位>} 推导。
     * 非桥接驱动（host / null / overlay）返回空串。
     */
    private String bridgeNameOf(Network net, String fullId) {
        Map<String, String> options = net.getOptions();
        if (options != null) {
            String explicit = options.get("com.docker.network.bridge.name");
            if (explicit != null && !explicit.isEmpty()) {
                return explicit;
            }
        }
        if (!"bridge".equals(net.getDriver())) {
            return "";
        }
        if ("bridge".equals(net.getName())) {
            return "docker0";
        }
        return fullId.length() >= 12 ? "br-" + fullId.substring(0, 12) : "";
    }

    // ==================== 汇总 ====================

    /** 汇总统计：接口计数、流量合计、Docker 网络与容器数、默认路由 */
    private NetworkInfo.NetworkSummary buildSummary(
            List<NetworkInfo.NetInterface> interfaces, List<NetworkInfo.DockerNetwork> networks) {
        NetworkInfo.NetworkSummary summary = new NetworkInfo.NetworkSummary();
        int total = 0;
        int up = 0;
        int physical = 0;
        int bridge = 0;
        int veth = 0;
        int dockerRelated = 0;
        long rxBytes = 0;
        long txBytes = 0;
        double rxRate = 0;
        double txRate = 0;
        for (NetworkInfo.NetInterface item : interfaces) {
            if (item.isLoopback()) {
                continue; // 回环流量不计入服务器网络统计
            }
            total++;
            if (item.isUp()) {
                up++;
            }
            switch (item.getCategory()) {
                case "physical" -> physical++;
                case "bridge" -> bridge++;
                case "veth" -> veth++;
                default -> {
                    // 其他类别仅计入总数
                }
            }
            if (item.isDockerRelated()) {
                dockerRelated++;
            }
            rxBytes += item.getRxBytes();
            txBytes += item.getTxBytes();
            rxRate += item.getRxRate();
            txRate += item.getTxRate();
        }
        summary.setTotal(total);
        summary.setUp(up);
        summary.setPhysical(physical);
        summary.setBridge(bridge);
        summary.setVeth(veth);
        summary.setDockerRelated(dockerRelated);
        summary.setDockerNetworks(networks.size());
        summary.setDockerContainers(distinctContainerCount(networks));
        summary.setTotalRxBytes(rxBytes);
        summary.setTotalTxBytes(txBytes);
        summary.setRxRate(round2(rxRate));
        summary.setTxRate(round2(txRate));
        fillDefaultRoute(summary);
        return summary;
    }

    /** 接入 Docker 网络的容器数（同一容器接入多个网络时只计一次） */
    private int distinctContainerCount(List<NetworkInfo.DockerNetwork> networks) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (NetworkInfo.DockerNetwork net : networks) {
            if (net.getContainers() == null) {
                continue;
            }
            for (NetworkInfo.NetworkAttachment attachment : net.getContainers()) {
                if (attachment.getContainerId() != null && !attachment.getContainerId().isEmpty()) {
                    ids.add(attachment.getContainerId());
                }
            }
        }
        return ids.size();
    }

    /** 解析 {@code /proc/net/route} 取默认网关与出口网卡（IPv4 为小端十六进制） */
    private void fillDefaultRoute(NetworkInfo.NetworkSummary summary) {
        String content = hostFs.readFile(PROC_NET_ROUTE);
        if (content.isBlank()) {
            return;
        }
        for (String line : content.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 8) {
                continue;
            }
            // Destination 与 Mask 均为 0 即默认路由
            if (!"00000000".equals(cols[1]) || !"00000000".equals(cols[7])) {
                continue;
            }
            summary.setDefaultInterface(cols[0]);
            summary.setDefaultGateway(hexToIpv4(cols[2]));
            return;
        }
    }

    /** {@code /proc/net/route} 的 IPv4 为小端十六进制（017CA8C0 → 192.168.124.1） */
    private String hexToIpv4(String hex) {
        if (hex == null || hex.length() != 8) {
            return "";
        }
        try {
            long value = Long.parseLong(hex, 16);
            return String.format(
                    "%d.%d.%d.%d", value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, (value >> 24) & 0xFF);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    // ==================== 工具 ====================

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析十六进制（支持 0x 前缀），失败归 0 */
    private long parseHex(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return value.startsWith("0x") || value.startsWith("0X")
                    ? Long.parseLong(value.substring(2), 16)
                    : Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
