package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 二维码识别请求。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class QrcodeDecodeBody {

    /** 待识别图片：PNG / JPEG / GIF 的 base64（不含 data: 前缀） */
    @NotBlank(message = "请先选择或拖入一张二维码图片")
    private String imageBase64;
}
