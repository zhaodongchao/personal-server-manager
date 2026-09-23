package com.serverpanel.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 参数配置 sys_config。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    /**
     * 主键序列化为字符串：新增记录是雪花 ID（19 位），超出 JS 安全整数范围，
     * 直接吐 Number 会被精度截断，编辑/删除会打到错行。基类字段只能在 getter 上覆写。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    private String configName;

    private String configKey;

    private String configValue;

    /** Y 内置不可删 / N 用户 */
    private String configType;

    private String remark;
}
