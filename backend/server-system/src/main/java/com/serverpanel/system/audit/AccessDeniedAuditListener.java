package com.serverpanel.system.audit;

import com.serverpanel.common.audit.AccessDeniedEvent;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.system.entity.SysAuditLog;
import com.serverpanel.system.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 越权尝试审计监听器：把在鉴权层被拒的请求写入 sys_audit_log。
 *
 * <p>与 {@link AuditAspect} 的分工是互补的：切面记录「进入了业务方法」的调用，
 * 本监听器记录「业务方法从未执行」的调用。action 固定为 {@value #ACTION}、
 * module 固定为 {@value #MODULE}，便于在审计页按模块筛出全部越权尝试。
 *
 * <p>落库失败绝不影响鉴权结果本身（403 照常返回）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDeniedAuditListener {

    /** 越权记录的模块标识，与 @Audit(module = ...) 的取值域并列 */
    public static final String MODULE = "access";

    /** 越权记录的动作标识 */
    public static final String ACTION = "access:denied";

    /** error_msg 列长度上限 */
    private static final int ERROR_MAX = 500;

    /** user_agent 列长度上限 */
    private static final int UA_MAX = 255;

    /** ip 列长度上限 */
    private static final int IP_MAX = 50;

    private final SysAuditLogMapper auditLogMapper;

    /** 是否记录越权尝试；不需要该视角或量太大时可关（无需发版） */
    @Value("${serverpanel.audit.record-denied:true}")
    private boolean recordDenied;

    /**
     * 落库一条越权记录。
     *
     * <p>注意本方法是同步的：审计行必须在 403 响应返回前写完，否则「刚被拒就去查审计」
     * 会查不到。单条主键插入，代价可忽略。
     */
    @EventListener
    public void onAccessDenied(AccessDeniedEvent event) {
        if (!recordDenied) {
            return;
        }
        SysAuditLog entry = new SysAuditLog();
        entry.setModule(MODULE);
        entry.setAction(ACTION);
        entry.setOperator(event.operator() == null || event.operator().isBlank()
            ? "anonymous" : event.operator());
        // 业务方法未执行，没有「类#方法」；列是 NOT NULL，用占位符而不是 null
        entry.setMethod("-");
        entry.setRequestUri(event.requestUri());
        entry.setRequestMethod(event.requestMethod());
        // 越权请求的入参不落库
        entry.setParams(null);
        entry.setResultCode(ErrorCode.FORBIDDEN.getCode());
        entry.setBizCode(ErrorCode.FORBIDDEN.getCode());
        entry.setRisky(0);
        entry.setErrorMsg(truncate(event.deniedBy(), ERROR_MAX));
        entry.setDurationMs(0L);
        entry.setIp(truncate(event.ip() == null ? "unknown" : event.ip(), IP_MAX));
        entry.setUserAgent(truncate(event.userAgent(), UA_MAX));
        try {
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("越权审计落库失败: {}", e.getMessage());
        }
    }

    /** 按列宽截断，避免 Data truncation 让整条审计丢失 */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
