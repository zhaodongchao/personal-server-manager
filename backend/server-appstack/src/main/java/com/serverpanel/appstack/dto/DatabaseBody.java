package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 数据库创建/编辑请求体。
 *
 * <p>dbName 同时作为数据库名与授权账号名（MySQL 账号最长 32 字符，
 * 故库名也限制为 32）。密码由服务端随机生成，不入库、不持久化。
 */
@Data
public class DatabaseBody {

    @NotBlank(message = "数据库名不能为空")
    @Size(max = 32, message = "数据库名最长 32 位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "库名仅支持字母、数字、下划线")
    private String dbName;

    /** 默认 utf8mb4 */
    @NotBlank(message = "字符集不能为空")
    @Pattern(regexp = "utf8mb4|utf8|latin1|gbk", message = "不支持的字符集")
    private String charset;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
