package com.serverpanel.system.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 头像规则收敛：三态解析、入库校验、渲染归一化。
 *
 * <p>头像在库中统一以「三态字符串」存放，取值语义见 {@code V5__add_sys_user_gender_avatar.sql}：
 * <ul>
 *   <li>{@code NULL} / 空串 —— 使用系统默认头像 {@value #DEFAULT_AVATAR}</li>
 *   <li>{@code preset:N}（N=1..{@value #PRESET_COUNT}） —— 内置预设头像，前端本地静态资源渲染</li>
 *   <li>{@code data:image/...;base64,...} —— 手动上传的自定义头像，base64 直接落库</li>
 * </ul>
 *
 * <p>本类不访问数据库，只做纯规则处理，便于单测与复用（用户管理 / 个人中心共用一套语义）。
 *
 * @author zhaodc
 * @since 2026-09-21
 */
@Component
public class AvatarSupport {

    /** 内置预设头像张数，与前端 {@code public/avatars/preset-N.svg} 一一对应 */
    public static final int PRESET_COUNT = 8;

    /** 系统默认头像（前端 public 下的本地静态资源，不走外部网络） */
    public static final String DEFAULT_AVATAR = "/avatar.svg";

    /** 预设头像的静态资源路径前缀 */
    public static final String PRESET_URL_PREFIX = "/avatars/preset-";

    /** 预设头像标记：preset:1 ~ preset:8 */
    private static final String PRESET_PREFIX = "preset:";

    private static final Pattern PRESET_PATTERN =
        Pattern.compile("^preset:[1-" + PRESET_COUNT + "]$");

    /** 仅放行 png / jpeg / webp 三种图片的 base64 data URL */
    private static final Pattern DATA_URL_PATTERN =
        Pattern.compile("^data:image/(?:png|jpeg|jpg|webp);base64,[A-Za-z0-9+/]+={0,2}$");

    /** base64 串长度上限，约对应 1MB 二进制（1MB ≈ 1398102 个 base64 字符） */
    private static final int MAX_BASE64_LENGTH = 1_400_000;

    /** 解码后字节数上限：1MB */
    private static final int MAX_DECODED_BYTES = 1_048_576;

    /** 自定义上传头像的入库前缀，用于识别与统计 */
    private static final String DATA_URL_PREFIX = "data:image/";

    /**
     * 解析并校验前端提交的头像值，返回可直接写库的归一化值。
     *
     * <p>三态语义：
     * <ul>
     *   <li>{@code null} —— 原样返回 {@code null}，调用方据此判断「本次不修改头像」</li>
     *   <li>空串 / 全空白 —— 返回 {@code null}，表示「清除头像，恢复系统默认」</li>
     *   <li>{@code preset:N} —— 校验后原样返回</li>
     *   <li>{@code data:image/...;base64,...} —— 校验格式与体积后原样返回</li>
     * </ul>
     *
     * @param input 前端提交的头像值，可为 null
     * @return 归一化后的入库值；null 表示默认/清除
     * @throws ServiceException 格式非法（400）或体积超限（413）
     */
    public String parseAndValidate(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        if (value.isEmpty()) {
            // 空串语义：显式清除，恢复系统默认
            return null;
        }
        if (PRESET_PATTERN.matcher(value).matches()) {
            return value;
        }
        if (!DATA_URL_PATTERN.matcher(value).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                "头像格式不受支持：仅支持内置预设头像，或 png / jpeg / webp 图片");
        }
        // 先按字符长度粗筛，避免对超大串做 base64 解码
        if (value.length() > MAX_BASE64_LENGTH) {
            throw new ServiceException(ErrorCode.PAYLOAD_TOO_LARGE,
                "头像过大，请压缩到 1MB 以内");
        }
        int bytes = decodedSize(value);
        if (bytes <= 0 || bytes > MAX_DECODED_BYTES) {
            throw new ServiceException(ErrorCode.PAYLOAD_TOO_LARGE,
                "头像过大，请压缩到 1MB 以内");
        }
        return value;
    }

    /**
     * 把库中的头像值转成前端可直接渲染的 {@code src}。
     *
     * <p>{@code preset:N} 会被映射为站内静态资源路径；自定义头像原样返回 data URL；
     * 空值回落系统默认头像。前端侧边栏 / 个人中心可直接使用，无需额外判断。
     *
     * @param stored 库中的原始头像值，可为 null
     * @return 可直接用于 img/avatar 的 src，保证非空
     */
    public String toRenderable(String stored) {
        if (stored == null || stored.isBlank()) {
            return DEFAULT_AVATAR;
        }
        if (stored.startsWith(PRESET_PREFIX)) {
            return PRESET_URL_PREFIX + stored.substring(PRESET_PREFIX.length()) + ".svg";
        }
        return stored;
    }

    /**
     * 判断库中头像是否为自定义上传的 base64 图片。
     *
     * @param stored 库中的原始头像值，可为 null
     * @return true 表示自定义上传
     */
    public boolean isCustom(String stored) {
        return stored != null && stored.startsWith(DATA_URL_PREFIX);
    }

    /**
     * 解码 base64 部分并返回字节数，用于体积校验。
     *
     * @param dataUrl 已通过格式校验的 data URL
     * @return 解码后字节数
     * @throws ServiceException base64 内容损坏时抛出
     */
    private int decodedSize(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        try {
            return Base64.getDecoder().decode(dataUrl.substring(comma + 1)).length;
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "头像数据损坏，请重新上传");
        }
    }
}
