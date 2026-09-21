package com.serverpanel.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户新增/编辑请求。
 */
@Data
public class UserBody {

    /** 仅新增时使用；编辑忽略 */
    private String password;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度 3-30 位")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "用户名须以字母开头，可含数字下划线")
    private String username;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长 30 字")
    private String nickname;

    @Size(max = 100, message = "邮箱过长")
    private String email;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    /** 性别 0未知 1男 2女 */
    @Min(value = 0, message = "性别取值非法")
    @Max(value = 2, message = "性别取值非法")
    private Integer gender;

    /**
     * 头像。三态语义：
     * <ul>
     *   <li>null —— 不修改（编辑时保持原值）</li>
     *   <li>空串 —— 清除，恢复系统默认头像</li>
     *   <li>preset:N / data:image/...;base64,... —— 设定为预设或自定义头像</li>
     * </ul>
     * 格式与体积校验由 AvatarSupport 负责。
     */
    private String avatar;

    /** 1 启用 0 停用 */
    private Integer status;

    private List<Long> roleIds;
}
