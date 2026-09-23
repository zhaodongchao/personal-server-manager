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
 * MySQL 取号原语：自增列（{@code AUTO_INCREMENT}）。
 *
 * <p>「事务回滚不回收 ID」这条结论在本实现里是<b>真的发生</b>而不是模拟的：
 * 参数 {@code rollbackAfter} / {@code rollbackCount} 会让面板在一个事务里真实插入
 * 若干行再回滚 —— 自增计数器已被推进且不会退回，于是紧接着的下一个号直接跳过一段。
 * 这就是自增 ID 空洞最典型的成因。
 *
 * <p>两个需要知道的细节：
 * <ul>
 *   <li>{@code LAST_INSERT_ID()} 是<b>会话函数</b>，必须与 INSERT 走同一个连接，
 *       所以并发会话数等于并发连接数；</li>
 *   <li>{@code auto_increment_increment} 是<b>会话变量</b>，用来演示分库分表的
 *       步长错开方案，必须在每个连接上单独设置。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Slf4j
@Component
public class MysqlIdSource implements IdSourceProvider {

    /** 默认自增表名（带 psm_ 前缀，与自动创建策略一致） */
    public static final String DEFAULT_TABLE = "psm_id_demo";

    private final JdbcSupport jdbc;

    public MysqlIdSource(JdbcSupport jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DbKind kind() {
        return DbKind.MYSQL;
    }

    @Override
    public String defaultTarget() {
        return DEFAULT_TABLE;
    }

    @Override
    public String targetOf(IdSourceSpec spec) {
        String name = spec.tableName();
        return (name == null || name.isBlank()) ? DEFAULT_TABLE : name.trim();
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
        String table = jdbc.checkIdentifier(targetOf(spec), "自增表名", true);
        try (Connection conn = jdbc.open(spec)) {
            if (tableExists(conn, spec.dbName(), table)) {
                return "表 " + table + " 已存在，无需创建";
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("create table " + table + " ("
                        + "id bigint not null auto_increment, "
                        + "created_at timestamp not null default current_timestamp, "
                        + "primary key (id)) engine=InnoDB");
            }
            return "已创建自增表 " + table + "（id BIGINT AUTO_INCREMENT 主键）";
        } catch (SQLException e) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_UNREACHABLE,
                    "初始化自增表失败：" + jdbc.rootMessage(e));
        }
    }

    @Override
    public IdSourceBatch fetch(IdSourceSpec spec, int count, Map<String, String> params) {
        jdbc.checkDatabase(spec.dbName());
        String table = jdbc.checkIdentifier(targetOf(spec), "自增表名", true);

        int sessions = PostgresIdSource.intParam(params, "sessions", 1, 1, 8);
        int increment = PostgresIdSource.intParam(params, "increment", 0, 0, 1_000);
        int rollbackAfter = PostgresIdSource.intParam(params, "rollbackAfter", 0, 0, 1_000);
        int rollbackCount = PostgresIdSource.intParam(params, "rollbackCount", 0, 0, 100);

        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> values = new ArrayList<>(count);
        List<String> extras = new ArrayList<>(count);

        if (rollbackAfter > 0 && rollbackCount > 0) {
            notes.add("第 " + rollbackAfter + " 个号之后会真实地开事务插入 " + rollbackCount
                    + " 行再回滚 —— 自增计数器已被推进且不会退回，"
                    + "所以紧接着的号会直接跳过一段，这就是自增 ID 空洞的成因");
        } else {
            notes.add("自增计数器由 InnoDB 维护，INSERT 时分配并立即持久化："
                    + "事务回滚不回收已分配的号，所以自增 ID 一定会有空洞");
        }
        if (sessions > 1) {
            notes.add("用 " + sessions + " 个独立连接并发插入取号 —— LAST_INSERT_ID() 是会话函数，"
                    + "必须与 INSERT 同连接，因此并发会话数等于并发连接数");
        }
        if (increment > 1) {
            notes.add("已在本会话设置 auto_increment_increment = " + increment
                    + "：这是分库分表把步长错开、避免各分片撞号的经典做法");
        }

        try (Connection conn = jdbc.open(spec)) {
            if (!tableExists(conn, spec.dbName(), table)) {
                throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_NOT_FOUND,
                        "表 " + table + " 不存在。请先在数据源上点「初始化」，"
                                + "或在数据源里改填一个已存在的自增表名");
            }
            if (increment > 1) {
                setSessionIncrement(conn, increment);
            }

            if (sessions == 1) {
                for (int i = 0; i < count; i++) {
                    if (rollbackAfter > 0 && rollbackCount > 0 && i + 1 == rollbackAfter) {
                        long wasted = rollbackThenDiscard(conn, table, rollbackCount);
                        warnings.add("事务回滚丢弃了 " + rollbackCount + " 个号"
                                + "（最后一个被浪费的号是 " + wasted + "）→ 下一个号会直接跳过这一段");
                        extras.add("此处回滚丢号，空洞 " + rollbackCount + " 个");
                    }
                    long id = insertAndGetId(conn, table);
                    values.add(String.valueOf(id));
                    extras.add("第 " + (i + 1) + " 次 INSERT");
                }
            } else {
                Connection[] pool = new Connection[sessions];
                try {
                    for (int i = 0; i < sessions; i++) {
                        pool[i] = jdbc.open(spec);
                        if (increment > 1) {
                            setSessionIncrement(pool[i], increment);
                        }
                    }
                    for (int i = 0; i < count; i++) {
                        int session = i % sessions;
                        long id = insertAndGetId(pool[session], table);
                        values.add(String.valueOf(id));
                        extras.add("会话 " + (session + 1));
                    }
                    if (rollbackAfter > 0 && rollbackCount > 0) {
                        warnings.add("并发会话模式下不演示回滚空洞（回滚会让会话间的号序更难读）；"
                                + "把并发会话数设为 1 即可看到真实的回滚丢号现象");
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

    private boolean tableExists(Connection conn, String dbName, String table) throws SQLException {
        try (var ps = conn.prepareStatement(
                "select count(*) from information_schema.tables "
                        + "where table_schema = ? and table_name = ?")) {
            ps.setString(1, dbName);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private void setSessionIncrement(Connection conn, int increment) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("set session auto_increment_increment = " + increment);
        }
    }

    private long insertAndGetId(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("insert into " + table + " () values ()");
        }
        return lastInsertId(conn);
    }

    /**
     * 真实地插入若干行再回滚。
     *
     * @return 被浪费掉的最后一个号（用于让「空洞」在界面上可验证）
     */
    private long rollbackThenDiscard(Connection conn, String table, int rows) throws SQLException {
        long last = 0;
        conn.setAutoCommit(false);
        try {
            for (int i = 0; i < rows; i++) {
                last = insertAndGetId(conn, table);
            }
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
        return last;
    }

    private long lastInsertId(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select last_insert_id()")) {
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
}
