package com.serverpanel.tools.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 二维码内容类型描述。
 *
 * <p>每种内容类型只是「把若干个字段拼成一串标准文本」，最终都编码成同一个
 * QR 码 —— 二维码标准里没有「WiFi 码」「名片码」之分，区别在于扫码端如何解释文本。
 *
 * <p>界面按服务端下发的字段定义渲染输入框，后端新增参数时前端无需改代码。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QrcodeTypeVO {

    /** 类型编码：TEXT / URL / ... */
    private String value;

    /** 展示名 */
    private String label;

    /** 一句话说明 */
    private String desc;

    /** 该类型的入参定义（提交时作为 params 的 key） */
    private List<FieldVO> fields;

    /**
     * 特别提示（可为 null）：用于说明该类型隐含的限制。
     *
     * <p>例如微信小程序：官方的「小程序码 / 菊花朵码」与公众号带场景值二维码，都必须由
     * 微信服务端 API（appid + secret 换 access_token）生成，服务端不再本地描绘；
     * 本工具产出的是「包含小程序路径/链接的标准二维码」，扫码后仍需微信自行跳转。
     */
    private String notice;
}
