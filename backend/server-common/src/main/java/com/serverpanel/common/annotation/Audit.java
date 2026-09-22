package com.serverpanel.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解。
 *
 * <p>标注在 Controller 写方法上，由 server-system 的
 * {@code com.serverpanel.system.audit.AuditAspect} 拦截，写入
 * sys_audit_log（操作人/入参/结果类别码/业务码/耗时/IP 等）。
 *
 * <p>两个属性职责必须分清，这是本次修正的核心：
 * <ul>
 *   <li>{@link #risky()} —— 只是「高危」标记。它落库到 sys_audit_log.risky，
 *       供审计页风险列展示，<b>不改变接口行为</b>；</li>
 *   <li>{@link #safe()} —— 真正的二级认证（step-up）闸门。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    /** 业务模块：system / file / ops / appstack */
    String module();

    /** 动作：如 user:add、file:delete、service:restart */
    String action();

    /**
     * 是否高危操作。
     *
     * <p>仅作为高危及标记落库（sys_audit_log.risky），供审计页风险列展示；
     * 它本身<b>不</b>改变接口行为 —— 原 Javadoc 声称「高危将同时要求二级认证」，
     * 但实现里从来没有对应拦截器，属文档与实现不一致，本次已拆分为两个属性。
     *
     * <p>需要二级认证请用 {@link #safe()}。
     */
    boolean risky() default false;

    /**
     * 是否要求二级认证（step-up）。
     *
     * <p>置 true 时，接口会在执行业务逻辑<b>之前</b>调用 Sa-Token 的
     * {@code StpUtil.checkSafe()}：未在安全窗口内完成二级认证
     * （POST /api/v1/auth/safe）则抛 NotSafeException，由全局异常处理器返回
     * {@code ErrorCode.AUTH_SAFE_REQUIRED}(1010)，前端据此弹出密码框并在
     * 认证成功后自动重放原请求。
     *
     * <p>默认 false：本能力按端点<b>显式开通</b>。原因是 risky=true 已用在 70+ 个
     * 端点上，若直接把 risky 等同于闸门，会一次性改变全站写操作的交互，
     * 并作废既有模块的验收结论。
     *
     * <p>可用 {@code serverpanel.audit.enforce-safe=false} 整体关停，无需发版。
     */
    boolean safe() default false;

    /**
     * 是否记录入参。
     *
     * <p>默认 true（既有行为不变）。置 false 时，该端点的 <b>入参整体不落库</b>：
     * sys_audit_log.params 写入固定占位 {@code [按端点配置不记录入参]}，
     * 但仍保留「谁、在什么时候、调用了哪个能力、结果如何」的留痕。
     *
     * <p>用途：处理口令 / Token / 密钥之类的端点（如 POST /auth/safe、日常工具的
     * 加解密与混淆接口）。这类端点如果记入参，等于把用户刚输入的敏感明文复制一份
     * 进审计表 —— 脱敏只能掩盖命中键名的值，而工具接口的入参键名是 text / key，
     * 不在敏感词表里，脱敏根本不会生效。
     *
     * <p>取舍与 /auth/safe 一致：宁可少记入参，也不能让审计表本身成为泄露源。
     */
    boolean recordParams() default true;
}
