package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * systemd 服务信息。
 */
@Data
public class ServiceInfo {

    /** 单元名，如 nginx.service */
    private String name;

    /** loaded / not-found 等 */
    private String load;

    /** active / inactive / failed */
    private String active;

    /** running / exited / dead 等 */
    private String sub;

    private String description;

    /** enabled / disabled / static / indirect */
    private String enabled;
}
