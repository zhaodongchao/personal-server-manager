package com.serverpanel.appstack.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.config.MysqlAdminProperties;
import com.serverpanel.appstack.dto.AdoptBody;
import com.serverpanel.appstack.dto.DatabaseBody;
import com.serverpanel.appstack.dto.DatabaseCreateResult;
import com.serverpanel.appstack.dto.DatabaseOverviewVO;
import com.serverpanel.appstack.dto.RestoreBody;
import com.serverpanel.appstack.entity.AppDatabase;
import com.serverpanel.appstack.mapper.AppDatabaseMapper;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 面板代管 MySQL 库管理。
 *
 * <p>通过管理连接执行 DDL/DCL：建库 → 建账号 → 授权；删库连带删账号；
 * 备份走 {@code mysqldump --result-file}，恢复走 {@code mysql -e "source ..."}，
 * 全程不经过 shell，敏感密码经 MYSQL_PWD 环境变量传递（不进 argv）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>库名/账号名严格白名单正则 {@code ^[a-zA-Z0-9_]+$}，作为标识符直接使用；</li>
 *   <li>密码由服务端生成（排除引号/反斜杠等危险字符），仅创建时返回一次；</li>
 *   <li>备份文件名限制在备份目录内的安全字符，且文件必须真实位于备份目录。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseService {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] PWD_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    /** MySQL 系统库：概览列表需过滤，避免把元数据库当成业务库展示/纳管 */
    private static final Set<String> SYSTEM_SCHEMAS =
        Set.of("information_schema", "mysql", "performance_schema", "sys");

    private final AppDatabaseMapper databaseMapper;
    private final CommandExecutor commandExecutor;
    private final MysqlAdminProperties adminProperties;

    // ==================== 查询 ====================

    public PageResult<AppDatabase> page(PageQuery query, String keyword) {
        Page<AppDatabase> page = databaseMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            new LambdaQueryWrapper<AppDatabase>()
                .like(keyword != null && !keyword.isBlank(), AppDatabase::getDbName, keyword)
                .orderByDesc(AppDatabase::getId));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    public List<String> charsets() {
        return List.of("utf8mb4", "utf8", "latin1", "gbk");
    }

    /**
     * 列出 MySQL 全部真实库，并合并面板纳管标记（浏览 + 纳管统一入口）。
     *
     * <p>数据来源 = information_schema.SCHEMATA 的真实库 ∪ 面板已纳管登记
     * （含库已被 MySQL 侧删除的悬空登记，避免「建库后消失」的困惑）；
     * 系统库过滤，返回按库名排序。
     */
    public List<DatabaseOverviewVO> overview() {
        // 已纳管登记（dbName -> 记录）
        Map<String, AppDatabase> managedByName = new TreeMap<>();
        for (AppDatabase d : databaseMapper.selectList(null)) {
            managedByName.put(d.getDbName(), d);
        }
        // MySQL 真实库（dbName -> 默认字符集）。
        // 管理连接不可用时（容器内无 mysql 客户端 / 账号未配 / 目标库不可达）
        // 降级为「仅展示已纳管登记」，避免整页 6006 失败；纳管能力随之不可用。
        Map<String, String> real = new TreeMap<>();
        try {
            for (String[] row : queryRows(
                    "SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA")) {
                String name = row[0] == null ? "" : row[0].trim();
                if (name.isEmpty() || SYSTEM_SCHEMAS.contains(name.toLowerCase())) {
                    continue;
                }
                real.put(name, row.length > 1 && row[1] != null ? row[1] : "");
            }
        } catch (ServiceException e) {
            log.warn("MySQL 管理连接不可用，概览已降级为仅展示已纳管库: {}", e.getMessage());
        }

        Set<String> all = new TreeSet<>(managedByName.keySet());
        all.addAll(real.keySet());
        List<DatabaseOverviewVO> result = new ArrayList<>(all.size());
        for (String name : all) {
            DatabaseOverviewVO vo = new DatabaseOverviewVO();
            vo.setDbName(name);
            vo.setCharset(real.get(name));
            AppDatabase m = managedByName.get(name);
            vo.setManaged(m != null);
            if (m != null) {
                vo.setId(String.valueOf(m.getId()));
                vo.setDbUser(m.getDbUser());
                vo.setRemark(m.getRemark());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 纳管一个 MySQL 中已存在的库：登记进 app_database（幂等判重）。
     *
     * <p>授权账号沿用「账号=库名」惯例；业务密码不落库（已有库的访问由外部管控，
     * 面板仅托管「删除 / 备份 / 恢复」等操作）。库必须在 MySQL 中真实存在。
     */
    public void adopt(AdoptBody body) {
        validateIdentifier(body.getDbName(), "库名");
        if (databaseMapper.selectCount(new LambdaQueryWrapper<AppDatabase>()
                .eq(AppDatabase::getDbName, body.getDbName())) > 0) {
            throw new ServiceException(ErrorCode.DB_EXISTS);
        }
        String charset = queryCharset(body.getDbName());
        if (charset == null) {
            throw new ServiceException(ErrorCode.DB_NOT_FOUND, "MySQL 中不存在该库");
        }
        AppDatabase db = new AppDatabase();
        db.setDbName(body.getDbName());
        db.setDbUser(body.getDbName());
        db.setCharset(charset);
        db.setRemark(body.getRemark());
        databaseMapper.insert(db);
    }

    // ==================== 建库 / 删库 ====================

    /**
     * 建库 + 建业务账号 + 授权；密码仅在返回结果中出现一次。
     */
    public DatabaseCreateResult create(DatabaseBody body) {
        validateIdentifier(body.getDbName(), "库名");
        if (databaseMapper.selectCount(new LambdaQueryWrapper<AppDatabase>()
                .eq(AppDatabase::getDbName, body.getDbName())) > 0) {
            throw new ServiceException(ErrorCode.DB_EXISTS);
        }
        String charset = body.getCharset() == null || body.getCharset().isBlank()
            ? "utf8mb4" : body.getCharset();
        String password = randomPassword();

        try {
            adminExec("CREATE DATABASE `" + body.getDbName() + "`"
                + " DEFAULT CHARACTER SET " + charset);
            adminExec("CREATE USER '" + body.getDbName() + "'@'%' IDENTIFIED BY '" + password + "'");
            adminExec("GRANT ALL PRIVILEGES ON `" + body.getDbName() + "`.*"
                + " TO '" + body.getDbName() + "'@'%'");
            adminExec("FLUSH PRIVILEGES");
        } catch (ServiceException e) {
            // 部分步骤失败时尽力清理，避免残留半成品
            cleanUpQuietly(body.getDbName());
            throw e;
        }

        AppDatabase db = new AppDatabase();
        db.setDbName(body.getDbName());
        db.setDbUser(body.getDbName());
        db.setCharset(charset);
        db.setRemark(body.getRemark());
        databaseMapper.insert(db);

        DatabaseCreateResult result = new DatabaseCreateResult();
        result.setDbName(body.getDbName());
        result.setUsername(body.getDbName());
        result.setPassword(password);
        result.setCharset(charset);
        return result;
    }

    public void delete(Long id) {
        AppDatabase db = databaseMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.DB_NOT_FOUND);
        }
        adminExec("DROP DATABASE IF EXISTS `" + db.getDbName() + "`");
        adminExec("DROP USER IF EXISTS '" + db.getDbName() + "'@'%'");
        databaseMapper.deleteById(id);
    }

    // ==================== 备份 / 恢复 ====================

    /** 备份指定库，返回备份文件名（备份目录下）。 */
    public String backup(Long id) {
        AppDatabase db = databaseMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.DB_NOT_FOUND);
        }
        Path dir = backupDir();
        String fileName = db.getDbName() + "_" + LocalDateTime.now().format(BACKUP_TS) + ".sql";
        Path target = dir.resolve(fileName);
        try {
            Map<String, String> env = adminEnv();
            ExecResult result = commandExecutor.exec(env,
                adminProperties.getMysqldumpBinary(),
                "--single-transaction",
                "--set-gtid-purged=OFF",
                "--result-file=" + target,
                db.getDbName());
            if (result.getExitCode() != 0) {
                Files.deleteIfExists(target);
                throw new ServiceException(ErrorCode.ERROR.getCode(),
                    "备份失败: " + errorText(result));
            }
            return fileName;
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "备份文件写入失败");
        }
    }

    /** 从备份目录中的指定文件恢复库。 */
    public void restore(Long id, RestoreBody body) {
        AppDatabase db = databaseMapper.selectById(id);
        if (db == null) {
            throw new ServiceException(ErrorCode.DB_NOT_FOUND);
        }
        Path dir = backupDir().toAbsolutePath().normalize();
        Path file = dir.resolve(body.getBackupFile()).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "备份文件不存在");
        }
        ExecResult result = runWithDb(db.getDbName(), "source " + file);
        if (result.getExitCode() != 0) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "恢复失败: " + errorText(result));
        }
    }

    /** 备份目录（懒创建） */
    private Path backupDir() {
        Path dir = Path.of(adminProperties.getBackupDir());
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.ERROR.getCode(), "备份目录不可用");
        }
    }

    // ==================== 底层执行 ====================

    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ServiceException(ErrorCode.DB_IDENTIFIER_INVALID,
                label + "仅支持字母、数字、下划线");
        }
    }

    /** 执行一条管理 SQL，失败抛 MYSQL_ADMIN_UNAVAILABLE/ERROR */
    private ExecResult adminExec(String... sqlStatements) {
        ExecResult result;
        try {
            result = commandExecutor.exec(adminEnv(), adminArgv(String.join("; ", sqlStatements) + ";"));
        } catch (ServiceException e) {
            throw new ServiceException(ErrorCode.MYSQL_ADMIN_UNAVAILABLE);
        }
        if (result.getExitCode() != 0) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "MySQL 执行失败: " + errorText(result));
        }
        return result;
    }

    /** 在指定库上执行 SQL（恢复用），错误由调用方判定 */
    private ExecResult runWithDb(String dbName, String sql) {
        try {
            List<String> argv = new ArrayList<>(List.of(
                adminProperties.getMysqlBinary(),
                "-h", adminProperties.getHost(),
                "-P", String.valueOf(adminProperties.getPort()),
                "-u", adminProperties.getUsername(),
                "-N",
                dbName,
                "-e", sql));
            return commandExecutor.exec(adminEnv(), argv.toArray(String[]::new));
        } catch (ServiceException e) {
            throw new ServiceException(ErrorCode.MYSQL_ADMIN_UNAVAILABLE);
        }
    }

    /**
     * 执行只读 SQL，返回按行拆分的单元格（tab 分隔）。
     *
     * <p>配合 {@code --batch} 去掉表格边框、{@code -N} 去掉列名，便于解析
     * information_schema 等元数据查询；失败抛 MYSQL_ADMIN_UNAVAILABLE / ERROR。
     */
    private List<String[]> queryRows(String sql) {
        List<String> argv = new ArrayList<>(List.of(
            adminProperties.getMysqlBinary(),
            "-h", adminProperties.getHost(),
            "-P", String.valueOf(adminProperties.getPort()),
            "-u", adminProperties.getUsername(),
            "-N", "--batch",
            "-e", sql));
        ExecResult result;
        try {
            result = commandExecutor.exec(adminEnv(), argv.toArray(String[]::new));
        } catch (ServiceException e) {
            throw new ServiceException(ErrorCode.MYSQL_ADMIN_UNAVAILABLE);
        }
        if (result.getExitCode() != 0) {
            throw new ServiceException(ErrorCode.ERROR.getCode(),
                "MySQL 查询失败: " + errorText(result));
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : result.getStdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    /** 查询指定库的默认字符集；库不存在返回 null */
    private String queryCharset(String dbName) {
        // dbName 已经过 lib 名白名单校验，可安全拼接（无注入风险）
        List<String[]> rows = queryRows("SELECT DEFAULT_CHARACTER_SET_NAME "
            + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '" + dbName + "' LIMIT 1");
        if (rows.isEmpty() || rows.get(0).length == 0 || rows.get(0)[0] == null) {
            return null;
        }
        return rows.get(0)[0];
    }

    private String[] adminArgv(String sqlScript) {
        return new String[] {
            adminProperties.getMysqlBinary(),
            "-h", adminProperties.getHost(),
            "-P", String.valueOf(adminProperties.getPort()),
            "-u", adminProperties.getUsername(),
            "-N",
            "-e", sqlScript
        };
    }

    private Map<String, String> adminEnv() {
        return Map.of("MYSQL_PWD",
            adminProperties.getPassword() == null ? "" : adminProperties.getPassword());
    }

    private void cleanUpQuietly(String dbName) {
        try {
            adminExec("DROP DATABASE IF EXISTS `" + dbName + "`");
            adminExec("DROP USER IF EXISTS '" + dbName + "'@'%'");
        } catch (ServiceException ignored) {
            log.warn("cleanup after failed db create ignored: {}", dbName);
        }
    }

    private String errorText(ExecResult result) {
        String msg = result.getStderr().isBlank() ? result.getStdout() : result.getStderr();
        return msg.isBlank() ? "未知错误" : msg.trim();
    }

    private String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PWD_CHARS[random.nextInt(PWD_CHARS.length)]);
        }
        return sb.toString();
    }
}
