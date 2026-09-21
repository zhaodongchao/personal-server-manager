package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 默认策略设置请求体。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallDefaultPolicyBody {

    /** allow / deny / reject */
    @Size(max = 16, message = "策略非法")
    private String incoming;

    @Size(max = 16, message = "策略非法")
    private String outgoing;

    @Size(max = 16, message = "策略非法")
    private String routed;

    /** 高危操作的二次确认关键字 */
    @Size(max = 64, message = "确认关键字过长")
    private String confirm;
}
