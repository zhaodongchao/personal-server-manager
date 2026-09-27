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

    /**
     * 滑块人机校验挑战：captcha:slider:{token} → "1"，TTL 由 serverpanel.captcha
     * .slider-ttl-seconds 控制（默认 120 秒）。校验（verify）时一次性消费，
     * 防止同一挑战被反复兑换发信令牌。
     */
    public static final String CAPTCHA_SLIDER_PREFIX = "captcha:slider:";

    /**
     * 发信令牌：captcha:send:{sendToken} → "1"，TTL 由 serverpanel.captcha
     * .send-token-ttl-seconds 控制（默认 60 秒）。/mail/code 消费它后才发码，
     * 单次使用（GETDEL 原子消费），保证「先过人机校验、再发邮件」不可绕过。
     */
    public static final String CAPTCHA_SEND_PREFIX = "captcha:send:";

    /**
     * 登录令牌：captcha:login:{loginToken} → "1"，TTL 同 send-token-ttl-seconds。
     * 由 {@code /auth/captcha/slider/verify}（purpose=login）签发，密码登录
     * {@code /auth/login} 携带它原子消费；与发信令牌（captcha:send:）隔离，
     * 杜绝「邮箱验证码场景拿到的令牌」被重放到「密码登录」场景。
     */
    public static final String CAPTCHA_LOGIN_PREFIX = "captcha:login:";

    /** 发信安全拦截：send:ip:min:{ip} → 计数，TTL 60 秒（每分钟窗口） */
    public static final String SEND_GUARD_IP_MIN = "send:ip:min:";

    /** 发信安全拦截：send:ip:hour:{ip} → 计数，TTL 3600 秒（每小时窗口） */
    public static final String SEND_GUARD_IP_HOUR = "send:ip:hour:";

    /**
     * 发信安全拦截封锁标记：send:ip:block:{ip} → 1，TTL 由 serverpanel.send-guard
     * .ip-block-minutes 控制。存在即拒绝该 IP 的发码请求（返回 1039），并提示剩余分钟。
     */
    public static final String SEND_GUARD_IP_BLOCK = "send:ip:block:";
}
