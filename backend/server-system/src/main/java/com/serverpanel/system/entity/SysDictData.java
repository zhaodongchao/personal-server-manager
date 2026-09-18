package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典数据 sys_dict_data。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_data")
public class SysDictData extends BaseEntity {

    private String dictType;

    private String dictLabel;

    private String dictValue;

    private Integer sort;

    private Integer status;

    /** 1 默认 */
    private Integer isDefault;

    private String remark;
}
