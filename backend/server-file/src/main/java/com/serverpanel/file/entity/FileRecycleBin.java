package com.serverpanel.file.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件回收站记录（file_recycle_bin，只增 + 清理）。
 */
@Data
@TableName("file_recycle_bin")
public class FileRecycleBin implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    /** 序列化为字符串：回收站记录是雪花 ID（19 位），超 JS 安全整数会被精度截断，还原/清理会打到错行 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 删除前原路径 */
    private String originPath;

    /** 回收站内路径 */
    private String trashPath;

    /** 原文件名 */
    private String fileName;

    /** 是否目录 */
    private Integer isDir;

    /** 字节数 */
    private Long size;

    /** 操作人 */
    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 过期时间（由定时清理） */
    private LocalDateTime expireAt;
}
