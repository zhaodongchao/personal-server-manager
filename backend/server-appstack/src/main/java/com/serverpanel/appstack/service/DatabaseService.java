package com.serverpanel.appstack.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.appstack.config.MysqlAdminProperties;
import com.serverpanel.appstack.dto.DatabaseBody;
import com.serverpanel.appstack.dto.DatabaseCreateResult;
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
