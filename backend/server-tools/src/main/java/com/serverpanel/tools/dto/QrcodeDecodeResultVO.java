package com.serverpanel.tools.dto;

import lombok.Data;

/**
 * 二维码识别结果。
 *
 * <p>与 JWT 验签同一个口径：<b>扫不出内容不是错误</b>，仍返回
 * {@code code=0} 且 {@code found=false} + 中文原因；只有图片本身不合法
 * （不是图片 / 解码失败）才走 7xxx 错误码。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Data
public class QrcodeDecodeResultVO {

    private boolean found;

    /** 识别出的文本 */
    private String text;

    /** 未能识别时的中文说明 */
    private String reason;

    /** 码制名称（如 QR_CODE），未识别时为空 */
    private String format;
}
