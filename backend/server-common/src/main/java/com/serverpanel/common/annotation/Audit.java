package com.serverpanel.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解。
 *
 * <p>标注在 Controller 写方法上，由 framework 层 AOP 切面拦截，
 * 异步写入 sys_audit_log（operator/入参/结果码/耗时/IP 等）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    /** 业务模块：system / file / ops / appstack */
    String module();

    /** 动作：如 user:add、file:delete、service:restart */
    String action();

    /** 是否高危操作（高危将同时要求二级认证，标记进审计日志） */
    boolean risky() default false;
}
