package com.serverpanel.ops.entity.mongo;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * 服务器配置「类别」文档（MongoDB，集合 {@code ops_server_config_category}）。
 *
 * <p>类别为系统预置、可扩展的元数据：sysctl（内核参数）/ limits（资源限制）/
 * sshd（SSH 配置）/ timesync（时间同步）。它只描述「这类配置长什么样、有多危险」，
 * <b>不含任何命令与路径的权威定义</b>——托管路径、校验方式、生效命令一律由宿主代理
 * （psm-hostagent）硬编码持有，后端不得把命令下发给代理，避免借写配置之名执行任意命令。
 *
 * <p>ID 使用 Mongo ObjectId 的 24 位十六进制字符串（而非 19 位雪花 Long）：既与项目
 * 其它 Mongo 文档一致，也天然规避 JS {@code Number.MAX_SAFE_INTEGER} 精度丢失。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@Document("ops_server_config_category")
public class OpsServerConfigCategory {

    /** 文档 ID（ObjectId 十六进制字符串，24 位，前端可安全传递） */
    @Id
    private String id;

    /** 类别键：sysctl / limits / sshd / timesync */
    @Indexed(unique = true)
    private String categoryKey;

    /** 类别名（中文） */
    private String name;

    /** 说明 */
    private String description;

    /**
     * 托管文件路径（仅供页面展示，属于宿主代理的元数据缓存）。
     * 读取接口会用 {@code sys.detect} 的权威值覆盖它，避免两处定义漂移。
     */
    private String managedFile;

    /** 风险级：L1 低风险 / L2 中风险 / L3 高风险（L3 生效必须键入关键字二次确认） */
    private String riskLevel;

    /** 生效说明（如「仅对新会话生效」「会重启 sshd 服务」） */
    private String applyHint;

    /** 排序 */
    private Integer sort;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
