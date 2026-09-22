package com.serverpanel.system.audit;

import cn.dev33.satoken.exception.NotSafeException;
import cn.dev33.satoken.stp.StpUtil;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 操作审计切面：拦截 @Audit 注解方法，写 sys_audit_log。
 *
 * <p>记录：操作人 / 模块 / 动作 / 方法 / URI / 入参（脱敏并截断 2KB）/
 * 结果类别码(result_code) / 业务码(biz_code) / 高危标记(risky) / 错误信息 /
 * 耗时 / IP / UA。
 *
 * <p>两件容易踩的事：
 * <ul>
 *   <li><b>入参必须脱敏</b>：user:add / user:edit 的请求体自带明文口令，直接 JSON 化
 *       会把口令写进审计表（历史脏数据已由 Flyway V12 一次性清理）；</li>
 *   <li><b>业务码不能只放在文本里</b>：业务失败统一记 result_code=500（保持既有语义
 *       不变），真实业务码进 biz_code，别让调用方去 error_msg 里抠数字。</li>
 * </ul>
 *
 * <p>另承担 @Audit(safe = true) 的二级认证闸门，总开关见下方 enforceSafe。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** params 字段最大 2KB */
    private static final int PARAMS_MAX = 2048;

    /** error_msg 字段最大 500（与 V1 建表宽度一致） */
    private static final int ERROR_MAX = 500;

    /** @Audit(recordParams = false) 时写入 params 的占位文本 */
    private static final String PARAMS_REDACTED = "[按端点配置不记录入参]";

    /**
     * 入参中的敏感键：键名含下列片段（大小写不敏感）的字符串值一律替换为 ***。
     * 只匹配「被引号包裹的值」，因此 "password":null 不受影响。
     */
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
        "(\"[A-Za-z0-9_]*(?:password|passwd|pwd|secret|token|credential|apikey|privatekey)"
            + "[A-Za-z0-9_]*\"\\s*:\\s*)\"[^\"]*\"",
        Pattern.CASE_INSENSITIVE);

    /** 敏感值替换模板（$1 为键名与冒号部分） */
    private static final String SENSITIVE_MASK = "$1\"***\"";

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /** @Audit(safe = true) 的二级认证闸门总开关，故障时可一键关停（无需发版） */
    @Value("${serverpanel.audit.enforce-safe:true}")
    private boolean enforceSafe;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable thrown = null;
        Object result = null;
        try {
            // 二级认证闸门（step-up）：放在 proceed 之前 —— 未认证就不进业务逻辑。
            // 抛出的 NotSafeException 会走下面的 catch 并落一条审计
            // （result_code=403、biz_code=1010），即「被 step-up 拦下」同样留痕。
            if (audit.safe() && enforceSafe) {
                StpUtil.checkSafe();
            }
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
        // 入参落库开关：false 时只留痕「用了哪个能力」，不复制明文
        entry.setParams(audit.recordParams() ? jsonSnippet(safeArgs) : PARAMS_REDACTED);

        entry.setRisky(audit.risky() || audit.safe() ? 1 : 0);

        if (thrown instanceof NotSafeException) {
            // 二级认证未通过：这是策略拒绝而非系统故障，按 403 记结果类别，
            // 业务码单独进 biz_code（1010），前端据此弹框做 step-up。
            entry.setResultCode(ErrorCode.FORBIDDEN.getCode());
            entry.setBizCode(ErrorCode.AUTH_SAFE_REQUIRED.getCode());
            entry.setErrorMsg(ErrorCode.AUTH_SAFE_REQUIRED.getMessage());
        } else if (thrown instanceof ServiceException se) {
            // 业务失败统一记 result_code=500（保持既有语义，不改写历史口径），
            // 真实业务码进 biz_code —— 不能再让调用方去 error_msg 文本里抠数字。
            entry.setResultCode(500);
            entry.setBizCode(se.getCode());
            entry.setErrorMsg(truncate(thrown.getMessage(), ERROR_MAX));
        } else if (thrown != null) {
            entry.setResultCode(500);
            entry.setErrorMsg(thrown.getMessage() == null
                ? thrown.getClass().getSimpleName()
                : truncate(thrown.getMessage(), ERROR_MAX));
        } else if (result instanceof R<?> r) {
            entry.setResultCode(r.getCode());
            // 未抛异常、以 R.fail 正常返回的（控制器内直接校验并返回），同样补业务码
            if (r.getCode() != ErrorCode.SUCCESS.getCode()) {
                entry.setBizCode(r.getCode());
            }
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

    /**
     * 入参 JSON 化：先脱敏再截断。
     *
     * <p>顺序不能反：先截断可能把 "password" 这个键名切掉，导致后续正则匹配不到，
     * 敏感值反而漏出去。
     */
    private String jsonSnippet(Object target) {
        try {
            String json = SENSITIVE_JSON.matcher(objectMapper.writeValueAsString(target))
                .replaceAll(SENSITIVE_MASK);
            return json.length() <= PARAMS_MAX ? json : json.substring(0, PARAMS_MAX);
        } catch (Exception e) {
            return String.valueOf(target);
        }
    }

    /** 按列宽截断 */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
