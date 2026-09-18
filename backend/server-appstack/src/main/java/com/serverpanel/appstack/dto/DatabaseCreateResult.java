package com.serverpanel.appstack.dto;

import lombok.Data;

/**
 * 建库结果：业务账号密码仅此一次返回，之后不再可查。
 */
@Data
public class DatabaseCreateResult {

    private String dbName;

    private String username;

    private String password;

    private String charset;
}
