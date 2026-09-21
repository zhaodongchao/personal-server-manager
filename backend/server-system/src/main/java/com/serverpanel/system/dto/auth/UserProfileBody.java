package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人中心 - 更新当前用户基本资料请求。
 */
@Data
public class UserProfileBody {

    @Size(max = 30, message = "昵称最长 30 字")
    private String realName;

    @Size(max = 100, message = "邮箱过长")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    /** 性别 0未知 1男 2女 */
    @Min(value = 0, message = "性别取值非法")
    @Max(value = 2, message = "性别取值非法")
    private Integer gender;

    /** 头像：null=不修改；空串=恢复默认；preset:N / data:image/...;base64,...=设定（格式与体积由 AvatarSupport 校验）*/
    private String avatar;

    @Size(max = 255, message = "个人简介最长 255 字")
    private String desc;
}
