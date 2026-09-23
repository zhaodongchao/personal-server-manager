package com.serverpanel.appstack.service.source;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.id.IdSourceSpec;

import lombok.extern.slf4j.Slf4j;

/**
 * 取号链路的 JDBC 支撑与安全闸门。
 *
 * <p><b>本类是全链路唯一放行 SQL 的地方</b>，因此三道闸门都收在这里：
 * <ol>
 *   <li><b>硬黑名单库</b> —— 面板自身库、Dify 生产库、各数据库的系统库。
 *       这些库无论配置怎么放开都不允许，因为它们被误写的代价是生产事故。</li>
 *   <li><b>允许库白名单</b> —— 默认只放开专用库 {@code psm_tools}，
 *       运维可通过 {@code serverpanel.id-source.allowed-databases} 扩展。</li>
 *   <li><b>标识符校验</b> —— 表名/序列名无法用占位符参数化（它们不是值而是语法元素），
 *       只能靠白名单正则兜底；自动创建的对象还额外要求 {@code psm_} 前缀，
 *       以便一眼分辨「面板建的」与「人建的」。</li>
 * </ol>
 *
 * <p>连接不走连接池，每次取号现开现关：取号是低频的交互式操作，
 * 为它维护一个连接池得不偿失，而短超时 + 快速失败对用户更友好。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Slf4j
@Component
public class JdbcSupport {

    /** 合法标识符：字母或下划线开头，只含字母数字下划线 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 自动创建的对象必须带此前缀 */
    public static final String AUTO_PREFIX = "psm_";

    /** 标识符长度上限（PostgreSQL 为 63，MySQL 为 64，取小值） */
    private static final int MAX_IDENTIFIER = 63;

    /**
     * 硬黑名单：与配置无关，永不放行。
     *
     * <p>前两个是面板库，中间两个是本机 Dify 的两个生产库（实测存在于同一 PG 实例上），
     * 其余是两种数据库的系统库。
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "server_panel",
            "dify",
            "dify_plugin",
            "postgres",
            "template0",
            "template1",
            "mysql",
            "information_schema",
            "performance_schema",
            "sys");

    private final Set<String> allowed;

    public JdbcSupport(
            @Value("${serverpanel.id-source.allowed-databases:psm_tools}") String allowedDatabases) {
        Set<String> set = new LinkedHashSet<>();
        if (allowedDatabases != null) {
            Arrays.stream(allowedDatabases.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(set::add);
        }
        this.allowed = Set.copyOf(set);
        log.info("取号数据源允许的库：{}（硬黑名单：{}）", this.allowed, FORBIDDEN);
    }

    /** 当前允许的库（供错误提示） */
    public Set<String> allowedDatabases() {
        return allowed;
    }

    /**
     * 校验库名。
     *
     * @throws ServiceException 库名为空、命中硬黑名单、或不在允许列表内
     */
    public void checkDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_IDENTIFIER_INVALID, "库名不能为空");
        }
        String key = dbName.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(key)) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_LIB_FORBIDDEN,
                    "库 " + dbName + " 属于面板自身库 / 生产库 / 系统库，取号不允许触碰它");
        }
        if (!allowed.contains(key)) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_LIB_FORBIDDEN,
                    "库 " + dbName + " 不在允许列表内（当前允许：" + allowed + "）。"
                            + "如需放开，请配置 serverpanel.id-source.allowed-databases");
        }
    }

    /**
     * 校验标识符（表名 / 序列名）。
     *
     * @param name           待校验名称
     * @param label          名称的语义（用于报错文案，如「序列名」）
     * @param requirePrefix  是否要求 {@code psm_} 前缀（自动创建的对象为 true）
     * @return 原样返回（已确认安全）
     */
    public String checkIdentifier(String name, String label, boolean requirePrefix) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_IDENTIFIER_INVALID,
                    label + "不能为空");
        }
        String value = name.trim();
        if (value.length() > MAX_IDENTIFIER) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_IDENTIFIER_INVALID,
                    label + "最长 " + MAX_IDENTIFIER + " 个字符");
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_IDENTIFIER_INVALID,
                    label + "「" + value + "」不合法：只能由字母、数字、下划线组成，且不能以数字开头");
        }
        if (requirePrefix && !value.toLowerCase(Locale.ROOT).startsWith(AUTO_PREFIX)) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_IDENTIFIER_INVALID,
                    label + "「" + value + "」需以 " + AUTO_PREFIX + " 开头 —— "
                            + "自动创建的对象统一带此前缀，以便区分「面板建的」与「人建的」");
        }
        return value;
    }

    /**
     * 打开连接。
     *
     * <p>显式加载驱动：Spring Boot 可执行 jar 的类加载器结构下，
     * {@code DriverManager} 偶发找不到 JDBC 4 自动注册的驱动，显式加载可消除这一不确定性。
     */
    public Connection open(IdSourceSpec spec) {
        try {
            if (spec.dbType() != null) {
                Class.forName(spec.dbType() == com.serverpanel.common.id.DbKind.MYSQL
                        ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
            }
        } catch (ClassNotFoundException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "缺少 " + spec.dbType() + " 驱动：" + e.getMessage());
        }
        try {
            return DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password());
        } catch (SQLException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "连接失败（" + spec.host() + ":" + spec.port() + "/" + spec.dbName() + "）："
                            + rootMessage(e));
        }
    }

    /** 读取数据库版本（同时证明连接可用） */
    public String serverVersion(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "未知版本";
        } catch (SQLException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "读取数据库版本失败：" + rootMessage(e));
        }
    }

    /** 把底层异常压成一句可读文案（保留最内层 message，避免整串堆栈塞进界面） */
    public String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
