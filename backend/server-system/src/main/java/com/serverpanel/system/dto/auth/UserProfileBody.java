package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.Email;
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

    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    @Size(max = 255, message = "个人简介最长 255 字")
    private String desc;
}
