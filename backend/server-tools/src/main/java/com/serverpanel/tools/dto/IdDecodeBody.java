package com.serverpanel.tools.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ID 反解请求体。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Data
public class IdDecodeBody {

    /** 方案编码（决定按哪套位分配拆解） */
    @NotBlank(message = "生成方案不能为空")
    private String scheme;

    /** 待反解的 ID（支持带连字符的 UUID、24 位十六进制 ObjectId、十进制长整数） */
    @NotBlank(message = "待反解的 ID 不能为空")
    private String value;

    /**
     * 时间基准（毫秒），仅雪花类需要：这几种方案的时间戳都存的是「相对某个基准的偏移」，
     * 不知道基准就无法反解出真实时间。留空则用该方案在本页的默认基准。
     */
    private Long epoch;
}
