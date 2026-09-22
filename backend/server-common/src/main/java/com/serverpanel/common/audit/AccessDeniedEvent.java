package com.serverpanel.common.audit;

/**
 * 越权访问事件：请求在鉴权层被拒绝（权限码或角色不足）。
 *
 * <p>为什么不让 {@code @Audit} 承担这件事：{@code @SaCheckPermission} 由 SaInterceptor
 * 在 HandlerInterceptor#preHandle 阶段执行，早于 Controller 方法上的 {@code @Audit} 切面，
 * 被拒请求永远不会进入切面。把切面前移是错的 —— 那会把「业务方法从未执行」记成「已执行」。
 * 因此越权尝试必须在鉴权层单独记录：
 *
 * <ul>
 *   <li>server-framework：全局异常处理器捕获 NotPermission / NotRole，发布本事件；</li>
 *   <li>server-system：监听事件，以 module=access / action=access:denied 落 sys_audit_log。</li>
 * </ul>
 *
 * <p>事件只携带审计所需的最小字段，<b>不携带请求体</b> —— 越权请求的入参不应落库。
 *
 * @param operator      操作人登录名，未登录为 null
 * @param requestUri    请求 URI
 * @param requestMethod HTTP 方法
 * @param deniedBy      被拒原因，如「缺少权限：appstack:job:delete」
 * @param ip            客户端 IP
 * @param userAgent     User-Agent
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
public record AccessDeniedEvent(String operator, String requestUri, String requestMethod,
                                String deniedBy, String ip, String userAgent) {
}
