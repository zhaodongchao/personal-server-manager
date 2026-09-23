package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色表 sys_role。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    /**
     * 主键序列化为字符串：新增角色是雪花 ID（19 位），超出 JS 安全整数范围，
     * 直接吐 Number 会被精度截断，后续编辑/删除/角色选择器会打到错行。
     * 基类字段只能在 getter 上覆写。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    private String roleName;

    /** 权限字符，如 admin */
    private String roleKey;

    private Integer sort;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;
}
