package com.serverpanel.tools.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片转换工具 options 响应：格式清单 + 上限 + 全局说明。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageConvertOptionsVO {

    /** 支持互转的格式清单 */
    private List<ImageFormatVO> formats;

    /** 各项上限 */
    private ImageLimitsVO limits;

    /** 全局行为说明（动图静帧、透明拍平等） */
    private List<String> notes;
}
