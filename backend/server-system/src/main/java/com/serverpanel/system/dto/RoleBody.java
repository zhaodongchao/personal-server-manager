package com.serverpanel.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色新增/编辑请求。
 */
@Data
public class RoleBody {

    @NotBlank(message = "角色名不能为空")
    @Size(max = 30)
    private String roleName;

    @NotBlank(message = "权限字符不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "权限字符须字母开头")
    @Size(max = 60)
    private String roleKey;

    private Integer sort;

    private Integer status;

    @Size(max = 255)
    private String remark;

    /** 菜单授权（编辑时同时重绑） */
    private List<Long> menuIds;
}
