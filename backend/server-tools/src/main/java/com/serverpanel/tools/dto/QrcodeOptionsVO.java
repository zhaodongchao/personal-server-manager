package com.serverpanel.tools.dto;

import lombok.Data;

import java.util.List;

/**
 * 二维码工具可选清单（服务端为唯一真源）。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class QrcodeOptionsVO {

    /** 内容类型清单 */
    private List<QrcodeTypeVO> types;

    /** 容错等级：L 7% / M 15% / Q 25% / H 30% —— 嵌 Logo 建议 H */
    private List<OptionVO> eccLevels;

    /** 码点样式：方点 / 圆点 / 圆角 */
    private List<OptionVO> dotStyles;

    /** 输出格式 */
    private List<OptionVO> formats;

    /** 图片边长默认值 */
    private int defaultSize;

    /** 图片边长下限 */
    private int minSize;

    /** 图片边长上限 */
    private int maxSize;

    /** 静默区（白边）默认值，单位：模块 */
    private int defaultMargin;

    /** 单次编码的内容上限（字符数） */
    private int maxContentChars;

    /** Logo 图片上限（base64 前的原始字节数） */
    private int maxLogoBytes;

    /** 待识别图片上限（base64 前的原始字节数） */
    private int maxImageBytes;

    /** Logo 占图片边长比例上限（超出会让二维码无法扫描） */
    private double maxLogoScale;
}
