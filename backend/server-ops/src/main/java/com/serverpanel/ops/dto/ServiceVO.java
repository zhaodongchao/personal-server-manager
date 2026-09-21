package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * systemd 服务列表行。
 *
 * <p>数据来源：{@code list-unit-files}（全集，含未加载/已禁用/已掩蔽/别名）
 * ∪ {@code list-units --all}（运行态）∪ {@code systemctl show}（实时字段）。
 * 这是相对旧实现的根本修正：旧实现以 {@code list-units} 为主表，导致
 * 「已安装但当前未加载」的单元（本机 225 - 167 = 58 个）在页面上完全不可见。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class ServiceVO {

    /** 单元名，如 nginx.service */
    private String name;

    private String description;

    /** loaded / not-found / masked */
    private String load;

    /** active / inactive / failed */
    private String active;

    /** running / exited / dead / failed */
    private String sub;

    /** enabled / disabled / static / indirect / masked / alias / generated */
    private String unitFileState;

    /** 是否被掩蔽（mask） */
    private boolean masked;

    /** 是否处于失败态 */
    private boolean failed;

    /** 是否为别名单元 */
    private boolean alias;

    /** 别名指向的规范单元名（仅 alias=true 时有值） */
    private String aliasOf;

    private Integer mainPid;

    /** 内存占用（字节）；取不到为 null */
    private Long memoryBytes;

    /** 已运行秒数（仅 active 时有值） */
    private Long uptimeSeconds;

    /** 重启次数 */
    private Integer restartCount;

    /** Unit 文件绝对路径 */
    private String fragmentPath;

    /** 最近一次状态变化的时刻原文（systemd 本地化格式，仅作展示） */
    private String stateChangeTimestamp;

    /**
     * 进入失败态的绝对时刻（epoch 毫秒）。
     *
     * <p>由「宿主机 uptime − StateChangeTimestampMonotonic」反推，**不依赖 systemd
     * 输出的本地化时间串**（后者带 CST 这类有歧义的时区缩写，Java 无法可靠解析）。
     */
    private Long failedSinceEpoch;

    /** 已失败时长（秒），仅 failed=true 时有值 */
    private Long failedSeconds;

    /** 是否命中保护清单 */
    private boolean protectedService;
}
