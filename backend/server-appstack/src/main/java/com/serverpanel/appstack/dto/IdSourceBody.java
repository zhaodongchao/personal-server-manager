package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 取号数据源的新增 / 编辑请求体。
 *
 * <p>{@code password} 在编辑时留空表示「不修改口令」—— 页面从不回显明文，
 * 因此不能用「提交空 = 清空口令」的语义，否则每次改备注都会把口令抹掉。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdSourceBody {

    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 60, message = "数据源名称最长 60 字符")
    private String name;

    @NotBlank(message = "数据库类型不能为空")
    private String dbType;

    @NotBlank(message = "主机不能为空")
    @Size(max = 120, message = "主机最长 120 字符")
    private String host;

    @NotNull(message = "端口不能为空")
    @Min(value = 1, message = "端口范围 1~65535")
    @Max(value = 65535, message = "端口范围 1~65535")
    private Integer port;

    @NotBlank(message = "库名不能为空")
    @Size(max = 64, message = "库名最长 64 字符")
    private String dbName;

    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号最长 64 字符")
    private String username;

    /** 口令；新增时必填，编辑时留空表示不修改 */
    @Size(max = 200, message = "口令最长 200 字符")
    private String password;

    @Size(max = 64, message = "表名最长 64 字符")
    @Pattern(regexp = "^$|^[A-Za-z_][A-Za-z0-9_]*$",
            message = "表名只能包含字母、数字、下划线，且不能以数字开头")
    private String tableName;

    @Size(max = 64, message = "序列名最长 64 字符")
    @Pattern(regexp = "^$|^[A-Za-z_][A-Za-z0-9_]*$",
            message = "序列名只能包含字母、数字、下划线，且不能以数字开头")
    private String sequenceName;

    /** 是否允许自动初始化取号对象 */
    private Boolean autoInit = Boolean.TRUE;

    /** 状态：启用 / 停用（API 同时接受 true/false 与 0/1） */
    private Boolean status = Boolean.TRUE;

    @Size(max = 200, message = "备注最长 200 字符")
    private String remark;
}
