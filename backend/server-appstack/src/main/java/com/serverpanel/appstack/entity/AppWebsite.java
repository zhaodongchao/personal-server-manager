package com.serverpanel.appstack.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.serverpanel.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Nginx 托管站点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_website")
public class AppWebsite extends BaseEntity {

    /** 主域名 */
    private String domain;

    private String siteName;

    /** proxy 反代 / static 静态 */
    private String siteType;

    /** 反代目标，如 http://127.0.0.1:3000 */
    private String upstream;

    /** 静态根目录 */
    private String staticRoot;

    private Integer sslEnabled;

    private String certPath;

    private String keyPath;

    /** 配置文件路径 */
    private String confPath;

    /** 1 运行 0 停用 */
    private Integer status;

    private String remark;
}
