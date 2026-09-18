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
}
