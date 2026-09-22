package com.serverpanel.ops.entity.mongo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * 服务器配置「配置项」文档（MongoDB，集合 {@code ops_server_config_item}）。
 *
 * <p>一个配置项 = 一行配置。<b>itemValue 为空表示「不托管这一项」</b>：渲染时整行不写入
 * 托管文件，因此发行版原有设置继续生效。这是本模块「非侵入 + 可回退」的基础语义。
 *
 * <p>itemKey 采用「渲染后行首原样」的约定，各类别略有差异，便于用同一套模型覆盖四类配置：
 * <ul>
 *   <li>sysctl：{@code net.core.somaxconn}（渲染为 {@code key = value}）</li>
 *   <li>limits：{@code * soft nofile}（渲染为 {@code key value}，即 domain/type/item 三段）</li>
 *   <li>sshd：{@code PermitRootLogin}（渲染为 {@code key value}）</li>
 *   <li>timesync：{@code NTP}（渲染为 {@code key=value}，统一挂在 {@code [Time]} 段下）</li>
 * </ul>
 *
 * <p>「同一类别内 itemKey 唯一」由 Service 层显式校验保证（不依赖 Mongo 索引自动创建，
 * 因为 {@code spring.mongodb.auto-index-creation} 默认未开启）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
@Document("ops_server_config_item")
public class OpsServerConfigItem {

    /** 文档 ID（ObjectId 十六进制字符串） */
    @Id
    private String id;

    /** 所属类别键 */
    @Indexed
    private String categoryKey;

    /** 参数名（渲染后行首原样，见类注释） */
    private String itemKey;

    /** 当前托管值；为空表示不托管该项 */
    private String itemValue;

    /** 系统默认值（由探测回填，只读参考） */
    private String defaultValue;

    /** 值类型：int / bool / enum / string / text */
    private String valueType;

    /** 枚举选项（valueType=enum 时生效） */
    private List<String> options;

    /** 推荐值（仅提示，不自动应用） */
    private String recommended;

    /** 说明 */
    private String description;

    /** 排序 */
    private Integer sort;

    /** 1 系统预置 0 用户新增 */
    private Integer builtin;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
