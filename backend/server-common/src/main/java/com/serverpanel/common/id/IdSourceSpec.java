package com.serverpanel.common.id;

import java.io.Serial;
import java.io.Serializable;

/**
 * 取号数据源的连接规格 —— 一个只读快照，取号期间有效，用完即弃。
 *
 * <p><b>为什么密码不参与 toString</b>：record 自动生成的 toString 会把所有分量打出来，
 * 而这个对象会被日志、异常消息、调试断点反复碰到。一旦口令进了日志，
 * 就等于把「专用库隔离」这层防护绕过去了。故此处显式覆盖为掩码。
 *
 * <p><b>为什么两个超时是硬编码的</b>：取号是个秒级操作，用户在前端点一下就等结果。
 * 连接不上却卡 30 秒毫无意义，反而会让面板线程池被占满。故取号链路一律用短超时，
 * 快速失败并把原始报错抛给用户 —— 探测连接（probe）与取号共用该规格。
 *
 * @param id           数据源主键（登记表 ID）
 * @param name         数据源名称（仅用于展示与日志）
 * @param dbType       数据库类型
 * @param host         主机
 * @param port         端口
 * @param dbName       库名
 * @param username     账号
 * @param password     口令（明文，仅在内存中短暂存在）
 * @param tableName    自增表名（{@code MYSQL_AUTO_INCREMENT} 用；可空，取号时回落到默认）
 * @param sequenceName 序列名（{@code SEQUENCE} 用；可空，取号时回落到默认）
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public record IdSourceSpec(
        Long id,
        String name,
        DbKind dbType,
        String host,
        int port,
        String dbName,
        String username,
        String password,
        String tableName,
        String sequenceName) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 建立 socket 的超时（毫秒），两种驱动语义不同，见 {@link #jdbcUrl()} */
    public static final int CONNECT_TIMEOUT_MS = 5_000;

    /** 单条语句超时（毫秒） */
    public static final int SOCKET_TIMEOUT_MS = 15_000;

    @Override
    public String toString() {
        return "IdSourceSpec[id=" + id + ", name=" + name + ", dbType=" + dbType
                + ", host=" + host + ", port=" + port + ", dbName=" + dbName
                + ", username=" + username + ", password=***"
                + ", tableName=" + tableName + ", sequenceName=" + sequenceName + ']';
    }

    /**
     * 拼 JDBC URL。
     *
     * <p>注意两种驱动的超时参数单位不同：PostgreSQL 的 {@code connectTimeout} 是<b>秒</b>、
     * {@code socketTimeout} 是<b>秒</b>；MySQL 的两个都是<b>毫秒</b>。写反了会得到
     * 「一次连接等 5000 秒」这种灾难性行为，故此处按类型分开拼。
     *
     * @return JDBC 连接串
     */
    public String jdbcUrl() {
        if (dbType == DbKind.MYSQL) {
            return "jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=" + CONNECT_TIMEOUT_MS
                    + "&socketTimeout=" + SOCKET_TIMEOUT_MS;
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + dbName
                + "?connectTimeout=" + (CONNECT_TIMEOUT_MS / 1000)
                + "&socketTimeout=" + (SOCKET_TIMEOUT_MS / 1000)
                + "&ApplicationName=ServerPanel-IdSource";
    }
}
