package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 纳管既有数据库请求体。
 *
 * <p>把 MySQL 中已存在的库登记进面板 app_database，纳入后续的删除 / 备份 / 恢复等管理。
 * 库名沿用 {@link DatabaseBody} 的标识符白名单约束（同时作为授权账号名）。
 */
@Data
public class AdoptBody {

    /** 待纳管的库名（须在 MySQL 中真实存在） */
    @NotBlank(message = "数据库名不能为空")
    @Size(max = 32, message = "数据库名最长 32 位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "库名仅支持字母、数字、下划线")
    private String dbName;

    @Size(max = 255, message = "备注过长")
    private String remark;
}