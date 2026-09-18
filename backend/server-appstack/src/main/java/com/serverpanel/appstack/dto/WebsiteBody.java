package com.serverpanel.appstack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 网站创建/更新请求体。
 */
@Data
public class WebsiteBody {

    private Long id;

    @NotBlank(message = "域名不能为空")
    @Size(max = 253, message = "域名过长")
    @Pattern(
        regexp = "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$",
        message = "域名格式非法")
    private String domain;

    @NotBlank(message = "站点名称不能为空")
    @Size(max = 60, message = "站点名称过长")
    private String siteName;

    /** proxy / static */
    @NotBlank(message = "站点类型不能为空")
    @Pattern(regexp = "proxy|static", message = "站点类型仅支持 proxy/static")
    private String siteType;

    /** 反代目标（proxy 必填） */
    @Size(max = 500, message = "反代目标过长")
    @Pattern(regexp = "^https?://\\S+$", message = "反代目标须为 http(s):// 开头且不含空白")
    private String upstream;

    /** 静态根目录（static 必填） */
    @Size(max = 255, message = "静态根目录过长")
    private String staticRoot;

    private Integer sslEnabled;

    @Size(max = 255, message = "证书路径过长")
    private String certPath;

    @Size(max = 255, message = "私钥路径过长")
    private String keyPath;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
