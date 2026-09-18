package com.serverpanel.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单新增/编辑请求。
 */
@Data
public class MenuBody {

    /** 父菜单 ID，0 为根 */
    @NotNull(message = "父菜单不能为空")
    private Long parentId;

    @NotBlank(message = "菜单名不能为空")
    @Size(max = 30)
    private String menuName;

    /** M 目录 / C 菜单 / F 按钮 */
    @NotBlank(message = "菜单类型不能为空")
    @Pattern(regexp = "^[MCF]$", message = "菜单类型须为 M/C/F")
    private String menuType;

    @Size(max = 200)
    private String routePath;

    @Size(max = 255)
    private String component;

    @Size(max = 100)
    private String perms;

    @Size(max = 60)
    private String icon;

    private Integer sort;

    private Integer visible;

    private Integer status;
}
