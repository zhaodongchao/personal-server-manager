package com.serverpanel.appstack.job.internal;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.serverpanel.appstack.entity.AppDatabase;
import com.serverpanel.appstack.service.DatabaseService;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.job.InternalTask;
import com.serverpanel.common.job.JobField;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置任务：备份面板代管的 MySQL 库。
 *
 * <p>复用既有 {@link DatabaseService#backup(Long)}（走 mysqldump --result-file，
 * 密码经环境变量传递不进 argv），本类只负责「选哪个库 / 选全部」这一层编排。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code databaseId} —— 备份指定库（app_database.id）；</li>
 *   <li>不填 —— 备份全部受管库。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbBackupTask implements InternalTask {

    /** 单次最多备份的库数量（与分页上限一致，防止误配导致长任务） */
    private static final int MAX_DATABASES = 200;

    private final DatabaseService databaseService;

    @Override
    public String code() {
        return "DB_BACKUP";
    }

    @Override
    public String label() {
        return "备份 MySQL 数据库";
    }

    @Override
    public String description() {
        return "备份指定库（databaseId）或全部受管库；留空参数即备份全部";
    }

    /**
     * 界面字段：数据库 ID。
     *
     * <p>ID 是雪花号，前端按字符串处理（数字输入框会丢精度），故这里用 {@code text}
     * 而不是 {@code number}。
     *
     * @author zhaodc
     * @since 2026-09-23 UTC+8
     */
    @Override
    public List<JobField> fields() {
        return List.of(JobField.text("databaseId", "数据库 ID", false,
                "app_database.id；留空即备份全部受管库。雪花号请直接粘贴，勿手输"));
    }

    @Override
    public Result execute(Map<String, String> params) {
        String id = params.get("databaseId");
        if (id != null && !id.isBlank()) {
            try {
                String path = databaseService.backup(Long.valueOf(id.trim()));
                return Result.ok("备份完成：" + path, path);
            } catch (NumberFormatException e) {
                return Result.fail("databaseId 不是合法数字：" + id);
            }
        }

        PageQuery query = new PageQuery();
        query.setPageSize(MAX_DATABASES);
        List<AppDatabase> databases = databaseService.page(query, null).getRecords();
        if (databases.isEmpty()) {
            return Result.ok("当前没有面板代管的数据库，无需备份");
        }
        StringBuilder output = new StringBuilder();
        int failed = 0;
        for (AppDatabase database : databases) {
            try {
                String path = databaseService.backup(database.getId());
                output.append("OK   ").append(database.getDbName()).append(" → ").append(path).append('\n');
            } catch (RuntimeException e) {
                failed++;
                output.append("FAIL ").append(database.getDbName()).append(" → ")
                        .append(e.getMessage()).append('\n');
            }
        }
        String summary = "共 " + databases.size() + " 个库，成功 " + (databases.size() - failed)
                + "，失败 " + failed;
        return failed == 0
                ? Result.ok(summary, output.toString())
                : Result.fail(summary + "；失败明细见输出", output.toString());
    }
}
