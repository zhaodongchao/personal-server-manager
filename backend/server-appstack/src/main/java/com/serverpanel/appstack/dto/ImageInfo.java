package com.serverpanel.appstack.dto;

import lombok.Data;

/**
 * Docker 镜像信息。
 */
@Data
public class ImageInfo {

    /** 短 ID */
    private String id;

    /** 仓库标签，如 nginx:latest */
    private String tag;

    private long size;

    /** 创建时间戳（秒） */
    private long created;
}
