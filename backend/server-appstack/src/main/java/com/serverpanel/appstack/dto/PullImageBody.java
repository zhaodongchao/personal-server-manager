package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 拉取镜像请求体。
 */
@Data
public class PullImageBody {

    @NotBlank(message = "镜像名不能为空")
    @Size(max = 200, message = "镜像名过长")
    private String image;
}
