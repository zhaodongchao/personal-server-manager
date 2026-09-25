package com.serverpanel.tools.cert;

import lombok.Getter;

/**
 * 证件类型枚举（解析入口与 options 清单的唯一真源）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Getter
public enum CertType {

    /** 居民身份证（GB 11643-1999，含 15 位升位） */
    ID_CARD("idCard", "身份证", "lucide:id-card", true,
            "请输入 15 或 18 位身份证号", "11010519491231002X"),
    /** 手机号（11 位，查号段表） */
    PHONE("phone", "手机号", "lucide:smartphone", false,
            "请输入 11 位手机号", "13812345678"),
    /** 银行卡（Luhn + BIN） */
    BANK_CARD("bankCard", "银行卡", "lucide:credit-card", false,
            "请输入银行卡卡号（12~23 位数字）", "6222020200112233335"),
    /** 统一社会信用代码（GB 32100-2015） */
    USCC("uscc", "统一社会信用代码", "lucide:building-2", true,
            "请输入 18 位统一社会信用代码", "91110105MA01ABCD5X"),
    /** 车牌号（GA 36-2018，含新能源） */
    PLATE("plate", "车牌号", "lucide:car", false,
            "请输入车牌号（普通 5 位 / 新能源 6 位序号）", "京AD12345"),
    /** 港澳居民来往内地通行证（回乡证） */
    HMT_PERMIT("hmtPermit", "回乡证", "lucide:plane-landing", false,
            "请输入回乡证号码（H/M 开头 + 8~10 位数字）", "H12345678"),
    /** 台湾居民来往大陆通行证（台胞证 / 居住证） */
    TW_PERMIT("twPermit", "台胞证", "lucide:plane-takeoff", true,
            "请输入台胞证（10 位）或台湾居民居住证（18 位）号码", "830101199001011237");

    /** 类型标识（接口传参用） */
    private final String key;

    /** 显示名 */
    private final String name;

    /** 图标（iconify） */
    private final String icon;

    /** 是否依赖行政区划数据（未同步时区划解析降级） */
    private final boolean needRegion;

    /** 输入框提示 */
    private final String placeholder;

    /** 示例 */
    private final String sample;

    CertType(String key, String name, String icon, boolean needRegion,
             String placeholder, String sample) {
        this.key = key;
        this.name = name;
        this.icon = icon;
        this.needRegion = needRegion;
        this.placeholder = placeholder;
        this.sample = sample;
    }

    /**
     * 按 key 查类型；不存在返回 null（由 Service 转错误码）。
     */
    public static CertType of(String key) {
        for (CertType value : values()) {
            if (value.key.equals(key)) {
                return value;
            }
        }
        return null;
    }
}
