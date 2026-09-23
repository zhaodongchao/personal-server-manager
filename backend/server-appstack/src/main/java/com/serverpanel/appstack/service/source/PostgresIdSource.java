package com.serverpanel.appstack.service.source;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.common.id.DbKind;
import com.serverpanel.common.id.IdSourceBatch;
import com.serverpanel.common.id.IdSourceProbe;
import com.serverpanel.common.id.IdSourceSpec;

import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL 取号原语：序列（{@code SEQUENCE}）。
 *
 * <p>与页面上的「参数化推演」不同，这里是真取号：{@code nextval} 会立即把序列的
 * 当前值向前推进并持久化 —— <b>即使外层事务回滚，号也不会退回</b>。这正是
 * 「数据库原生自增类一定存在空洞」这一结论的真实来源，而不是模拟出来的现象。
 *
 * <p>关于并发：PostgreSQL 的 {@code CACHE} 是<b>会话级</b>预分配 —— 某会话首次
 * {@code nextval} 时会一次性预取 CACHE 个号放进自己的内存，之后不再访问序列。
 * 因此本实现支持指定多个并发会话，用来真实复现「各会话内部连续、全局交错跳号」。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Slf4j
@Component
public class PostgresIdSource implements IdSourceProvider {

    /** 默认序列名（带 psm_ 前缀，与自动创建策略一致） */
    public static final String DEFAULT_SEQUENCE = "psm_id_seq";

    private final JdbcSupport jdbc;

    public PostgresIdSource(JdbcSupport jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DbKind kind() {
        return DbKind.POSTGRESQL;
    }

    @Override
    public String defaultTarget() {
        return DEFAULT_SEQUENCE;
    }

    @Override
    public String targetOf(IdSourceSpec spec) {
        String name = spec.sequenceName();
        return (name == null || name.isBlank()) ? DEFAULT_SEQUENCE : name.trim();
    }

    @Override
    public IdSourceProbe probe(IdSourceSpec spec) {
        try {
            jdbc.checkDatabase(spec.dbName());
            try (Connection conn = jdbc.open(spec)) {
                return IdSourceProbe.pass(jdbc.serverVersion(conn));
            }
        } catch (ServiceException e) {
            return IdSourceProbe.fail(e.getMessage());
        } catch (Exception e) {
            return IdSourceProbe.fail(jdbc.rootMessage(e));
        }
    }

    @Override
    public String initialize(IdSourceSpec spec) {
        jdbc.checkDatabase(spec.dbName());
        String seq = jdbc.checkIdentifier(targetOf(spec), "序列名", true);
        try (Connection conn = jdbc.open(spec)) {
            if (sequenceExists(conn, seq)) {
                return "序列 " + seq + " 已存在，无需创建";
            }
            try (Statement st = conn.createStatement()) {
                st.execute("create sequence " + seq + " increment by 1 cache 1");
            }
            return "已创建序列 " + seq + "（INCREMENT BY 1、CACHE 1）";
        } catch (SQLException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "初始化序列失败：" + jdbc.rootMessage(e));
        }
    }

    @Override
    public IdSourceBatch fetch(IdSourceSpec spec, int count, Map<String, String> params) {
        jdbc.checkDatabase(spec.dbName());
        String seq = jdbc.checkIdentifier(targetOf(spec), "序列名", true);

        int sessions = intParam(params, "sessions", 1, 1, 8);
        long increment = longParam(params, "increment", 0L, 0L, 100_000L);
        long cache = longParam(params, "cache", 0L, 0L, 10_000L);

        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (Connection conn = jdbc.open(spec)) {
            if (!sequenceExists(conn, seq)) {
                throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_NOT_FOUND,
                        "序列 " + seq + " 不存在。请先在数据源上点「初始化」，"
                                + "或在数据源里改填一个已存在的序列名");
            }
            if (increment > 0 || cache > 0) {
                StringBuilder ddl = new StringBuilder("alter sequence ").append(seq);
                if (increment > 0) {
                    ddl.append(" increment by ").append(increment);
                }
                if (cache > 0) {
                    ddl.append(" cache ").append(cache);
                }
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl.toString());
                }
                notes.add("已按请求调整序列：" + (increment > 0 ? "INCREMENT BY " + increment + " " : "")
                        + (cache > 0 ? "CACHE " + cache : ""));
            }

            if (sessions == 1) {
                notes.add("序列 " + seq + " 由 PostgreSQL 维护，nextval 每次返回一个号并立即落盘："
                        + "事务回滚不会把它退回来，所以序列号必然会有空洞");
            } else {
                notes.add("用 " + sessions + " 个独立连接并发取号，用于复现「每个会话内部连续、"
                        + "全局交错」的真实现象 —— 这正是一套代码里多个连接池时的实际观感");
            }
            if (cache > 1) {
                warnings.add("CACHE " + cache + " 是「会话级预分配」：每个会话首次取号时就一次性"
                        + "预取 " + cache + " 个放进自己的内存，之后不再访问序列。"
                        + "会话结束（或应用重启）时没用完的号被直接丢弃 → 这是空洞最隐蔽的来源");
            }

            List<String> values = new ArrayList<>(count);
            List<String> extras = new ArrayList<>(count);

            if (sessions == 1) {
                for (int i = 0; i < count; i++) {
                    values.add(String.valueOf(nextval(conn, seq)));
                    extras.add("第 " + (i + 1) + " 次 nextval");
                }
            } else {
                Connection[] pool = new Connection[sessions];
                try {
                    for (int i = 0; i < sessions; i++) {
                        pool[i] = jdbc.open(spec);
                    }
                    for (int i = 0; i < count; i++) {
                        int session = i % sessions;
                        values.add(String.valueOf(nextval(pool[session], seq)));
                        extras.add("会话 " + (session + 1));
                    }
                } finally {
                    for (Connection c : pool) {
                        closeQuietly(c);
                    }
                }
            }
            return IdSourceBatch.of(values, extras, notes, warnings);
        } catch (SQLException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "取号失败：" + jdbc.rootMessage(e));
        }
    }

    // ==========================================================================
    // 内部实现
    // ==========================================================================

    private boolean sequenceExists(Connection conn, String seq) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select to_regclass('" + seq + "')")) {
            return rs.next() && rs.getObject(1) != null;
        }
    }

    /**
     * 取下一个号。
     *
     * <p>{@code nextval} 的入参是 {@code regclass}（对象名）而不是值，因此这里用
     * 单引号包裹的字面量而非占位符：一来占位符对「对象名」并不带来额外的注入防护，
     * 二来部分驱动版本上 text→regclass 的隐式转换会失败。安全性由
     * {@link JdbcSupport#checkIdentifier} 的白名单正则保证（只允许 [A-Za-z0-9_]）。
     */
    private long nextval(Connection conn, String seq) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select nextval('" + seq + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("关闭取号连接失败：{}", e.getMessage());
        }
    }

    public static int intParam(Map<String, String> params, String key, int def, int min, int max) {
        long v = longParam(params, key, def, min, max);
        return (int) v;
    }

    public static long longParam(Map<String, String> params, String key, long def, long min, long max) {
        if (params == null) {
            return def;
        }
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                    "参数 " + key + " 不是合法整数：" + raw);
        }
        if (value < min || value > max) {
            throw new ServiceException(ErrorCode.TOOLS_ID_PARAM_INVALID,
                    "参数 " + key + " 超出范围（" + min + "~" + max + "）：" + value);
        }
        return value;
    }
}
