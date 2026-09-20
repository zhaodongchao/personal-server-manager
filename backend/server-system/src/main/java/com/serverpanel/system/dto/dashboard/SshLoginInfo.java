package com.serverpanel.system.dto.dashboard;

import lombok.Data;

/**
 * 工作台 - SSH 登录记录。
 */
@Data
public class SshLoginInfo {

    /** 登录时间（服务器本地时间 yyyy-MM-dd HH:mm:ss） */
    private String time;

    /** 登录用户 */
    private String username;

    /** 来源 IP */
    private String ip;

    /** 来源端口 */
    private String port;

    /** 认证方式：publickey / password */
    private String method;
}
