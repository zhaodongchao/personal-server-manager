package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 数据库恢复请求体。
 */
@Data
public class RestoreBody {

    /** 备份文件名（仅允许备份目录内的常规文件名，防路径穿越） */
    @NotBlank(message = "备份文件不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+\\.sql$", message = "备份文件名非法")
    private String backupFile;
}
