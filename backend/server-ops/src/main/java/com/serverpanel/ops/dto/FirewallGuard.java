package com.serverpanel.ops.dto;

import lombok.Data;

import java.util.List;

/**
 * 防火墙生存线信息（防锁死）。
 *
 * <p>在面板上改防火墙最大的风险是把自己锁在门外：一旦默认策略为 deny 且
 * SSH/面板端口没有放行规则，下一次连接就再也进不来。这里把「当前会话依赖的端口」
 * 与「这些端口现在的放行情况」显式算出来，前端据此做红色警示与二次确认。
 *
 * @author zhaodc
 * @since 2026-09-21 UTC+8
 */
@Data
public class FirewallGuard {

    /** SSH 实际监听端口（由宿主机 sshd -T 解析） */
    private List<Integer> sshPorts;

    /** 面板端口（后端与前端） */
    private List<Integer> panelPorts;

    /** 发起请求的客户端 IP */
    private String clientIp;

    /** 由外部程序（Fail2Ban 等）管理的规则条数 */
    private int foreignRuleCount;

    /** 当前 SSH 端口是否有放行规则 */
    private boolean sshAllowed;

    /** 风险提示（为空表示无风险） */
    private List<String> warnings;
}
