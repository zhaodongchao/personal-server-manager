package com.serverpanel.common.core;

import java.io.Serial;
import java.io.Serializable;

import com.serverpanel.common.exception.ErrorCode;

import lombok.Data;

/**
 * 统一响应包装。
 *
 * <p>约定：code = 0 表示成功；非 0 为业务/系统错误码（见 {@link ErrorCode}）。
 * 前端按 code 判断成功与否，失败时 toast message。
 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码：0 成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 数据负载 */
    private T data;

    public static <T> R<T> ok() {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return build(errorCode.getCode(), message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    private static <T> R<T> build(int code, String message, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(data);
        return r;
    }
}
