package com.serverpanel.common.mybatis;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.Data;

/**
 * 业务表通用基类：雪花主键 + 创建/更新时间（由 MetaObjectHandler 自动填充）。
 *
 * <p>仅适用于同时拥有 created_at / updated_at 列的表；
 * 审计/登录日志等只增表不继承本类。
 */
@Data
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，应用侧雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 创建时间（应用填充，列有默认值兜底） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（应用填充，列有 ON UPDATE 兜底） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
