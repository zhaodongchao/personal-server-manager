package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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

    private String roleName;

    /** 权限字符，如 admin */
    private String roleKey;

    private Integer sort;

    /** 1 启用 0 停用 */
    private Integer status;

    private String remark;
}
