package com.serverpanel.common.id;

import java.util.List;
import java.util.Map;

/**
 * 取号数据源 SPI —— 「ID 生成器」里自增 / 序列两类方案的真连库通道。
 *
 * <p><b>为什么 SPI 放在 server-common</b>：这两个方案的语义属于 ID 生成器
 * （{@code server-tools}），但它们依赖「数据源登记表 + JDBC 连接管理」，
 * 而这些属于应用栈（{@code server-appstack}）。若让 tools 直接依赖 appstack，
 * 就出现了横向模块依赖 —— 与本项目的分层约定相悖。故把接口上提到 common，
 * 由 appstack 贡献实现，tools 只依赖接口（设计 ADR-2，与 {@code InternalTask} 同源）。
 *
 * <p><b>实现侧的硬性约束</b>（写在接口上是因为它属于契约，不属于某一实现）：
 * <ol>
 *   <li><b>库黑名单</b>：禁止把取号对象建到面板自身库与其它业务生产库上 ——
 *       {@code server_panel}、{@code dify}、{@code dify_plugin}、{@code postgres}
 *       及常见系统库一律拒绝。取号会产生真实的写副作用，隔离失效的代价是污染生产数据。</li>
 *   <li><b>对象前缀白名单</b>：自动创建的表 / 序列必须以 {@code psm_} 开头。</li>
 *   <li><b>标识符校验</b>：所有拼进 SQL 的表名 / 序列名必须先过
 *       {@code ^[A-Za-z_][A-Za-z0-9_]*$} —— 标识符无法参数化，只能靠白名单正则兜底。</li>
 * </ol>
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public interface IdSourceGateway {

    /**
     * 全部启用中的数据源（供前端下拉）。
     *
     * @return 选项列表；无数据源时返回空列表（不返回 {@code null}）
     */
    List<IdSourceOption> options();

    /**
     * 探测连通性并返回数据库版本。
     *
     * @param sourceId 数据源 ID
     * @return 探测结果；连同失败原因一并返回，不抛异常
     */
    IdSourceProbe probe(Long sourceId);

    /**
     * 初始化取号对象（建序列 / 建自增表），幂等。
     *
     * <p>会先校验库黑名单与对象前缀，再执行 {@code IF NOT EXISTS} 语义的 DDL。
     *
     * @param sourceId 数据源 ID
     * @return 本次实际做了什么（用于界面回显）
     */
    String initialize(Long sourceId);

    /**
     * 真连库取号。
     *
     * @param sourceId 数据源 ID
     * @param scheme   方案编码：{@code MYSQL_AUTO_INCREMENT} 或 {@code SEQUENCE}
     * @param count    取号个数（1 ~ 1000）
     * @param params   方案参数（可空）。当前识别：
     *                 {@code sessions}（并发会话数，>1 时用多连接交错取号）、
     *                 {@code autoInit}（为 {@code "false"} 时不自动建对象）
     * @return 取号结果（含原理说明与风险提示）
     */
    IdSourceBatch fetch(Long sourceId, String scheme, int count, Map<String, String> params);
}
