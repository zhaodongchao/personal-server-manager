package com.serverpanel.ops.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 防火墙规则写入/删除请求体。
 *
 * <p>写入与删除共用这一个体：写入靠 {@code target + protocol + action + source}，
 * 删除靠 {@code no + fingerprint}（按编号删，但用指纹防编号漂移）。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallRuleBody {

    /** 写入时必填 */
    @Valid
    private FirewallRuleTarget target;

    /** tcp / udp / any */
    private String protocol;

    /** allow / deny / reject / limit */
    @NotNull(message = "动作不能为空")
    private String action;

    /** 来源，any 或 CIDR/IP */
    @Size(max = 64, message = "来源过长")
    private String source;

    /** 备注；面板会统一加 "psm:" 前缀以便识别来源 */
    @Size(max = 120, message = "备注过长")
    private String comment;

    /** 高危操作的二次确认关键字（如 "SSH 22"） */
    @Size(max = 64, message = "确认关键字过长")
    private String confirm;

    /** 删除用：规则编号 */
    private Integer no;

    /** 删除用：前端提交时的规则指纹，后端校验「编号指向的仍是同一条规则」 */
    @Size(max = 200, message = "指纹非法")
    private String fingerprint;

    /** 删除 Fail2Ban 等外部规则时的强制确认 */
    private boolean force;
}
