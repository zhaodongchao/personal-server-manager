package com.serverpanel.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 防火墙规则写入请求体。
 */
@Data
public class FirewallRuleBody {

    @Min(value = 1, message = "端口范围 1-65535")
    @Max(value = 65535, message = "端口范围 1-65535")
    private int port;

    @NotBlank(message = "协议不能为空")
    @Pattern(regexp = "tcp|udp", message = "协议仅支持 tcp/udp")
    private String protocol;

    /** allow / deny（firewalld 仅支持 allow） */
    @NotBlank(message = "动作不能为空")
    @Pattern(regexp = "allow|deny", message = "动作仅支持 allow/deny")
    private String action;

    /** 可选来源 IP/网段，如 192.168.1.0/24 */
    @Pattern(regexp = "[0-9a-fA-F.:/]{3,64}", message = "来源格式非法")
    private String source;
}
