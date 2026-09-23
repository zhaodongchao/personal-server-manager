package com.serverpanel.common.id;

/**
 * 数据源连通性探测结果。
 *
 * @param ok            是否连通
 * @param message       结论文案（成功给版本号，失败给原始报错）
 * @param serverVersion 数据库版本（探测失败时为 {@code null}）
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public record IdSourceProbe(boolean ok, String message, String serverVersion) {

    public static IdSourceProbe pass(String serverVersion) {
        return new IdSourceProbe(true, "连接成功：" + serverVersion, serverVersion);
    }

    public static IdSourceProbe fail(String message) {
        return new IdSourceProbe(false, message, null);
    }
}
