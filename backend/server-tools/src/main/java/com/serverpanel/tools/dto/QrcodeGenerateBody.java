package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 二维码生成请求。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class QrcodeGenerateBody {

    /** 内容类型：TEXT / URL / WIFI / VCARD / EMAIL / TEL / SMS / GEO / EVENT / BITCOIN / WECHAT_MINIAPP */
    @NotBlank(message = "内容类型不能为空")
    private String contentType;

    /** 按类型而定的参数（字段定义由 GET /options 下发） */
    private Map<String, String> params;

    /** 目标边长（像素），实际输出会吸附到模块的整数倍，见返回值 realSize */
    private Integer size;

    /** 静默区宽度（模块），默认 4 —— 国标要求四周留白，否则部分扫码器难以定位 */
    private Integer margin;

    /** 容错等级：L / M / Q / H，默认 M */
    private String ecc;

    /** 前景色 #RRGGBB，默认 #000000 */
    private String fgColor;

    /** 背景色 #RRGGBB，默认 #FFFFFF */
    private String bgColor;

    /** 渐变第二色（为空则不上渐变） */
    private String gradientColor;

    /** 码点样式：SQUARE / DOT / ROUNDED，默认 SQUARE */
    private String dotStyle;

    /** 中心 Logo：PNG/JPEG 的 base64（不含 data: 前缀） */
    private String logoBase64;

    /** Logo 占边长比例，默认 0.2，上限由 options.maxLogoScale 给出 */
    private Double logoScale;

    /** Logo 是否裁成圆形 */
    private Boolean logoRound;

    /** 输出格式：PNG / JPEG，默认 PNG */
    private String format;
}
