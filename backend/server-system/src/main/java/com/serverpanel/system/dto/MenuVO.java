package com.serverpanel.system.dto;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 菜单树节点（管理页）。
 */
@Data
public class MenuVO {

    /** 序列化为字符串：菜单主键是雪花 ID（19 位），超 JS 安全整数会被精度截断 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 同为雪花 ID：前端拿它做树 shuttle/父子比对，必须与 id 同口径 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    private String menuName;

    private String menuType;

    private String routePath;

    private String component;

    private String perms;

    private String icon;

    private Integer sort;

    private Integer visible;

    private Integer status;

    private List<MenuVO> children;
}
