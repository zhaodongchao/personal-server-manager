package com.serverpanel.appstack.dto;

import lombok.Data;

/**
 * Docker 容器信息。
 */
@Data
public class ContainerInfo {

    /** 短 ID（12 位） */
    private String id;

    /** 名称（去斜杠） */
    private String name;

    private String image;

    /** running / exited / created / paused / restarting */
    private String state;

    private String status;

    /** 端口映射摘要，如 8080->80/tcp */
    private String ports;
}
