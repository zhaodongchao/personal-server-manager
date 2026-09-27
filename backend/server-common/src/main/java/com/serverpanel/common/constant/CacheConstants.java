package com.serverpanel.common.constant;

/**
 * Redis 缓存 key 约定。
 *
 * <p>命名规范：{域}:{对象}:{限定}，冒号分层。
 */
public final class CacheConstants {

    private CacheConstants() {}

    /** 监控实时帧环形缓存（List，LTRIM 保留最近 720 条 = 1 小时） */
    public static final String MON_FRAME_RECENT = "mon:frame:recent";

    /** 登录失败计数：login:fail:{username}:{ip} → 失败次数（带过期） */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";

    /** 登录锁定标记：login:lock:{username}:{ip} → 1（带过期） */
    public static final String LOGIN_LOCK_PREFIX = "login:lock:";

    /** 字典数据缓存：dict:data:{dictType} → List<DictData> */
    public static final String DICT_DATA_PREFIX = "dict:data:";

    /** 系统参数配置缓存：config:{configKey} → value */
    public static final String CONFIG_PREFIX = "config:";

    /**
     * OAuth 授权 state（防 CSRF + 防重放）：oauth:state:{完整 state 串} →
     * JSON {intent, provider, userId?}，带 TTL（默认 300 秒）。
     * 校验方式：消费时 delete 该 key，返回 false 即已用/过期/伪造。
     */
    public static final String OAUTH_STATE_PREFIX = "oauth:state:";

    /**
     * 邮箱验证码：mail:code:{purpose}:{email} → 6 位数字码，
     * 带 TTL（默认 300 秒）；校验用 GETDEL 原子消费（一次性，防重放）。
     * purpose 取值 login / register，两类码互不通用。
     */
    public static final String MAIL_CODE_PREFIX = "mail:code:";

    /** 邮箱发码冷却：mail:cooldown:{purpose}:{email} → 1，TTL 60 秒（同邮箱同场景） */
    public static final String MAIL_COOLDOWN_PREFIX = "mail:cooldown:";

    /** 邮箱日发码量：mail:daily:{email} → 计数（TTL 24 小时滑动窗口，默认上限 10） */
    public static final String MAIL_DAILY_PREFIX = "mail:daily:";

    /** 验证码失败计数：mail:fail:{purpose}:{email} → 次数，达到上限（默认 5）作废当前码 */
    public static final String MAIL_FAIL_PREFIX = "mail:fail:";
}
