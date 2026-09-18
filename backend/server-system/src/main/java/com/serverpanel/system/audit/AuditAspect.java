package com.serverpanel.system.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.entity.SysAuditLog;
import com.serverpanel.system.mapper.SysAuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作审计切面：拦截 @Audit 注解方法，异步落 sys_audit_log。
 *
 * <p>记录：操作人 / 模块 / 动作 / 方法 / URI / 入参（截断2KB）/
 * 结果码 / 错误信息 / 耗时 / IP / UA。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** params 字段最大 2KB */
    private static final int PARAMS_MAX = 2048;

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable thrown = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            thrown = e;
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            try {
                saveLog(joinPoint, audit, duration, result, thrown);
            } catch (Exception e) {
                log.error("Failed to persist audit log", e);
            }
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, Audit audit, long duration,
                         Object result, Throwable thrown) {
        SysAuditLog entry = new SysAuditLog();
        entry.setModule(audit.module());
        entry.setAction(audit.action());
        entry.setDurationMs(duration);

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        entry.setMethod(signature.getDeclaringTypeName() + "#" + signature.getName());

        // 入参：剔除 servlet/文件对象后 JSON 化并截断
        List<Object> safeArgs = new ArrayList<>();
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                || arg instanceof MultipartFile || arg instanceof byte[]) {
                continue;
            }
            safeArgs.add(arg);
        }
        entry.setParams(jsonSnippet(safeArgs));

        if (thrown != null) {
            entry.setResultCode(500);
            String msg = thrown.getMessage();
            entry.setErrorMsg(msg == null ? thrown.getClass().getSimpleName()
                : msg.substring(0, Math.min(msg.length(), 500)));
        } else if (result instanceof R<?> r) {
            entry.setResultCode(r.getCode());
        } else {
            entry.setResultCode(0);
        }

        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            entry.setRequestUri(request.getRequestURI());
            entry.setRequestMethod(request.getMethod());
            entry.setIp(request.getRemoteAddr());
            entry.setUserAgent(request.getHeader("User-Agent"));
        }

        // 操作人：登录场景（login 本身）可能未登录，容错处理
        try {
            entry.setOperator(LoginHelper.getUsername());
        } catch (Exception e) {
            entry.setOperator("anonymous");
        }

        // 异步写库（虚拟线程），不阻塞主流程
        Thread.ofVirtual().name("audit-writer").start(() -> {
            try {
                auditLogMapper.insert(entry);
            } catch (Exception e) {
                log.error("Audit log insert failed: {}", e.getMessage());
            }
        });
    }

    private String jsonSnippet(Object target) {
        try {
            String json = objectMapper.writeValueAsString(target);
            return json.length() <= PARAMS_MAX ? json : json.substring(0, PARAMS_MAX);
        } catch (Exception e) {
            return String.valueOf(target);
        }
    }
}
